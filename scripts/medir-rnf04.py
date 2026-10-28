#!/usr/bin/env python3
"""Medição do RNF04 (RF05d, V01-2): boleta → negócio e núcleo do matching, sob carga local documentada.

Contraparte já em repouso (CONTA-003) e agressores Limit IOC de 1 unidade (CONTA-002) enviados em sequência, cada um
só depois do terminal do anterior: mede processamento, não espera por contraparte nem fila. Os percentis saem da
diferença dos histogramas Prometheus antes/depois da fase medida (aquecimento excluído) e são limites superiores do
bucket. O veredito compara o p99 com a meta; em ambiente compartilhado (`--contexto ci-hospedado`) é só indicativo.
Atenção: redefine as carteiras de teste CONTA-002/003 (preparação local), apagando o estado delas.
"""
import argparse
import base64
import json
import math
import os
import platform
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import uuid
from importlib import import_module
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
mercado = import_module("mercado")

METAS = {"prospero_ordens_boleta_negocio_seconds": 0.050, "prospero_matching_comando_seconds": 0.001}


LINHA = re.compile(r'^(?P<serie>[A-Za-z_:][A-Za-z0-9_:]*)(?:\{(?P<rotulos>(?:[^}"]|"(?:[^"\\]|\\.)*")*)\})?\s+(?P<valor>\S+)')
LE = re.compile(r'(?:^|,)\s*le="([^"]*)"')
TETO_METRICAS = 8 * 1024 * 1024
MINIMO_AMOSTRAS_DEDICADO = 1000
TETO_ORDENS_POR_CONTA = 1000
COMPRADORES_PADRAO = "CONTA-002,CONTA-001"
TRACES_AMOSTRADOS = 100
# Span de entrada de cada serviço na trajetória de uma ordem (mesmos nomes de testar-trajetoria.py).
SALTOS = {"front-office": "POST /api/v1/ordens", "middle-office": "risco.avaliar",
          "matching-engine": "matching.consumir", "back-office": "posicoes.consumir"}


def buckets(origem, nome):
    """Contagens cumulativas por limite `le` do histograma Prometheus do serviço e a soma `_sum` em segundos."""
    with urllib.request.urlopen(origem + "/q/metrics", timeout=10) as resposta:
        texto = resposta.read(TETO_METRICAS + 1)
    if len(texto) > TETO_METRICAS:
        raise SystemExit(f"/q/metrics de {origem} excede {TETO_METRICAS} bytes.")
    return ler_buckets(texto.decode("utf-8", "replace"), nome)


def ler_buckets(texto, nome):
    """Soma as séries do histograma por `le`; o valor é o primeiro após os rótulos (exemplar `# {...}` ignorado).

    Linhas malformadas ou valores não finitos são ignorados e contados em `ignoradas`; `soma` e `contagem` (de `_sum`
    e `_count`, independentes dos buckets) ficam None quando ausentes.
    """
    contagens = {"soma": None, "contagem": None, "ignoradas": 0}
    agregadas = {nome + "_sum": "soma", nome + "_count": "contagem"}
    for linha in texto.splitlines():
        achado = LINHA.match(linha)
        if not achado or achado["serie"] not in (nome + "_bucket", *agregadas):
            continue
        le = LE.search(achado["rotulos"] or "")
        try:
            valor = float(achado["valor"])
            chave = agregadas.get(achado["serie"]) or (float(le[1]) if le else None)
        except ValueError:
            chave = None
        if chave is None or not math.isfinite(valor) or (not isinstance(chave, str) and math.isnan(chave)):
            contagens["ignoradas"] += 1
            continue
        contagens[chave] = (contagens.get(chave) or 0) + valor
    return contagens


def media_independente(antes, depois):
    """Média por `_sum`/`_count`, sem usar buckets; None se ausente, sem amostras ou com contador reiniciado."""
    valores = (antes.get("soma"), depois.get("soma"), antes.get("contagem"), depois.get("contagem"))
    if None in valores or valores[3] - valores[2] <= 0 or valores[1] < valores[0]:
        return None
    return (valores[1] - valores[0]) / (valores[3] - valores[2])


def percentil(antes, depois, q):
    """Menor limite de bucket que cobre a fração q das amostras novas; None sem amostras."""
    limites = sorted(le for le in depois if not isinstance(le, str))
    total = depois.get(float("inf"), 0) - antes.get(float("inf"), 0)
    if total <= 0:  # sem amostras novas ou contador reiniciado entre as leituras
        return None, 0
    for le in limites:
        if depois[le] - antes.get(le, 0) >= q * total:
            return le, int(total)
    return float("inf"), int(total)


def tem_resolucao(depois, meta):
    """O histograma precisa ter um bucket exatamente na meta; sem ele o p99 vira um limite superior mais largo."""
    return any(not isinstance(le, str) and math.isclose(le, meta, rel_tol=0, abs_tol=1e-12) for le in depois)


def classificar(p99, meta, contexto="dedicado"):
    """Somente limite superior estritamente abaixo da meta comprova a desigualdade exigida."""
    if p99 is None:
        return None
    prefixo = "indicativo: " if contexto == "ci-hospedado" else ""
    return prefixo + ("cumprido" if p99 < meta else "não cumprido")


def validar_plano(amostras, aquecimento, compradores, contexto):
    """Recusa planos que não permitem veredito: poucas amostras (dedicado) ou estouro do teto de ordens por conta."""
    if contexto == "dedicado" and amostras < MINIMO_AMOSTRAS_DEDICADO:
        raise SystemExit(f"Modo dedicado exige pelo menos {MINIMO_AMOSTRAS_DEDICADO} amostras; recebido {amostras}.")
    if amostras < 1 or not 0 <= aquecimento <= 150 or not compradores:
        raise SystemExit("Amostras >= 1, aquecimento 0–150 e ao menos um comprador.")
    por_conta = math.ceil((amostras + aquecimento) / len(compradores))
    if por_conta > TETO_ORDENS_POR_CONTA:
        raise SystemExit(f"{por_conta} ordens por comprador excedem o teto de {TETO_ORDENS_POR_CONTA} por conta e sessão.")
    return por_conta


def duracoes_por_salto(conteudo, trace_id):
    """Jaeger API v3 (OTLP JSON por linha): {serviço: (início_ns, duração_s)} dos spans de entrada de um trace."""
    achados = {}
    for linha in conteudo.splitlines():
        if not linha.strip():
            continue
        for recurso in json.loads(linha).get("result", {}).get("resourceSpans", []):
            atributos = recurso.get("resource", {}).get("attributes", [])
            servico = next((a.get("value", {}).get("stringValue") for a in atributos if a.get("key") == "service.name"), None)
            for escopo in recurso.get("scopeSpans", []):
                for span in escopo.get("spans", []):
                    identidade = span.get("traceId", "")
                    if identidade != trace_id:
                        try:
                            identidade = base64.b64decode(identidade, validate=True).hex()
                        except (ValueError, TypeError):
                            continue
                    if identidade != trace_id or SALTOS.get(servico) != span.get("name"):
                        continue
                    try:
                        inicio, fim = int(span["startTimeUnixNano"]), int(span["endTimeUnixNano"])
                    except (KeyError, ValueError):
                        continue
                    if fim >= inicio and servico not in achados:
                        achados[servico] = (inicio, (fim - inicio) / 1e9)
    return achados


def atribuicao(traces):
    """p50/p99 (amostral) da duração de cada salto e do atraso de início dele em relação ao POST do FO."""
    def pct(valores, q):
        ordenados = sorted(valores)
        return ordenados[min(len(ordenados) - 1, int(len(ordenados) * q))] if ordenados else None

    resultado = {}
    completos = [t for t in traces if "front-office" in t]
    for servico in SALTOS:
        duracoes = [t[servico][1] for t in completos if servico in t]
        atrasos = [(t[servico][0] - t["front-office"][0]) / 1e9 for t in completos if servico in t]
        resultado[servico] = {"traces": len(duracoes), "duracao_p50_s": pct(duracoes, 0.50), "duracao_p99_s": pct(duracoes, 0.99),
                              "inicio_apos_post_p50_s": pct(atrasos, 0.50), "inicio_apos_post_p99_s": pct(atrasos, 0.99)}
    return {"traces_consultados": len(traces), "saltos": resultado,
            "nota": "valores amostrais exatos do Jaeger; atraso de início inclui o trânsito Kafka entre serviços"}


def coletar_atribuicao(origem_jaeger, traces_ids):
    """Consulta o Jaeger por trace; falha de coleta nunca invalida a medição de latência."""
    coletados = []
    for trace in traces_ids:
        try:
            with urllib.request.urlopen(origem_jaeger.rstrip("/") + "/api/v3/traces/" + trace, timeout=10) as r:
                coletados.append(duracoes_por_salto(r.read(4 * 1024 * 1024).decode("utf-8", "replace"), trace))
        except Exception:
            continue
    return atribuicao(coletados)


def ambiente():
    def saida(*cmd):
        try:
            return subprocess.check_output(cmd, text=True, stderr=subprocess.DEVNULL, timeout=20).strip()
        except (OSError, subprocess.SubprocessError):
            return "indisponível"
    memoria = "indisponível"
    try:
        memoria = next(l for l in Path("/proc/meminfo").read_text().splitlines() if l.startswith("MemTotal"))
    except OSError:
        pass
    return {"sha": saida("git", "rev-parse", "--short", "HEAD"), "kernel": platform.release(),
            "cpus": os.cpu_count(), "memoria": memoria, "docker": saida("docker", "version", "--format", "{{.Server.Version}}"),
            "limites": "384 MiB e 1 CPU por serviço Java (docker-compose.yml)"}


def verificar_orcamento(origem, token, necessarias):
    """Margem conservadora por leitura limitada; não reserva vagas contra outro emissor."""
    def recusar():
        raise SystemExit("Margem de ordens não comprovada; nenhuma preparação ou ordem foi enviada.")

    if type(necessarias) is not int or not 1 <= necessarias <= 1000:
        recusar()
    ids, cursores = set(), set()
    cursor = None
    for _ in range(10):
        parametros = {"limite": 100}
        if cursor is not None:
            parametros["cursor"] = cursor
        try:
            pagina = mercado.http("GET", origem.rstrip("/") + "/api/v1/ordens?" +
                                  urllib.parse.urlencode(parametros), token)
        except Exception:
            recusar()
        if (not isinstance(pagina, dict) or "proximoCursor" not in pagina or
                not isinstance(pagina.get("ordens"), list) or len(pagina["ordens"]) > 100):
            recusar()
        for ordem in pagina["ordens"]:
            identificador = ordem.get("id") if isinstance(ordem, dict) else None
            try:
                valido = isinstance(identificador, str) and str(uuid.UUID(identificador)) == identificador
            except (ValueError, AttributeError):
                valido = False
            if not valido or identificador in ids:
                recusar()
            ids.add(identificador)
        if len(ids) + necessarias > 1000:
            recusar()
        cursor = pagina["proximoCursor"]
        if cursor is None:
            return len(ids)
        if not isinstance(cursor, str) or not 1 <= len(cursor) <= 2048 or cursor in cursores:
            recusar()
        cursores.add(cursor)
    recusar()


def executar(args):
    compradores = [c.strip() for c in args.compradores.split(",") if c.strip()]
    if any(c not in mercado.CONTAS or c == "CONTA-003" for c in compradores) or len(set(compradores)) != len(compradores):
        raise SystemExit("Compradores devem ser contas distintas de CONTA-001..004, exceto a vendedora CONTA-003.")
    por_conta = validar_plano(args.amostras, args.aquecimento, compradores, args.contexto)
    total = args.amostras + args.aquecimento
    vendedor = mercado.token("ordens", 900, "CONTA-003")
    tokens = {c: mercado.token("ordens", 900, c) for c in compradores}
    orcamento = {"compradorExistentes": {c: verificar_orcamento(args.origem, tokens[c], por_conta) for c in compradores},
                 "vendedorExistentes": verificar_orcamento(args.origem, vendedor, 1),
                 "compradorNecessarias": por_conta, "vendedorNecessarias": 1, "limite": TETO_ORDENS_POR_CONTA}
    for c in compradores:
        mercado.preparar_carteira(args.origem_mo, c, "1000000.00", {}, prazo=60)
    mercado.preparar_carteira(args.origem_mo, "CONTA-003", "0.00", {args.ticker: total}, prazo=60)

    def enviar(tok, corpo, extras=None):
        return mercado.http("POST", args.origem + "/api/v1/ordens", tok, corpo,
                            {"X-Idempotency-Key": "rnf04-" + uuid.uuid4().hex, **(extras or {})})

    oferta = enviar(vendedor, {"ticker": args.ticker, "lado": "VENDA", "tipo": "LIMIT", "validade": "DIA",
                               "quantidade": total, "precoLimite": args.preco})
    mercado.aguardar_campos(args.origem, vendedor, f"/api/v1/ordens/{oferta['id']}", {"estado": "ABERTA"})
    nomes = list(METAS)
    origens = {nomes[0]: args.origem, nomes[1]: args.origem_me}
    antes, cliente, traces = None, [], []
    for i in range(total):
        if i == args.aquecimento:
            antes = {n: buckets(origens[n], n) for n in nomes}
        conta = compradores[i % len(compradores)]
        extras, trace = None, None
        if args.origem_jaeger and i >= total - TRACES_AMOSTRADOS:
            trace = uuid.uuid4().hex
            extras = {"traceparent": f"00-{trace}-{uuid.uuid4().hex[:16]}-01"}
        inicio = time.perf_counter()
        ordem = enviar(tokens[conta], {"ticker": args.ticker, "lado": "COMPRA", "tipo": "LIMIT", "validade": "IOC",
                                       "quantidade": 1, "precoLimite": args.preco}, extras)
        mercado.aguardar_campos(args.origem, tokens[conta], f"/api/v1/ordens/{ordem['id']}", {"estado": "EXECUTADA"})
        if i >= args.aquecimento:
            cliente.append(time.perf_counter() - inicio)
        if trace:
            traces.append(trace)
    depois = {n: buckets(origens[n], n) for n in nomes}
    resultado = {"ambiente": ambiente(), "plano": vars(args), "orcamentoAntes": orcamento, "metricas": {}}
    for n in nomes:
        p50, amostras = percentil(antes[n], depois[n], 0.50)
        p99, _ = percentil(antes[n], depois[n], 0.99)
        media = media_independente(antes[n], depois[n])
        veredito = classificar(p99, METAS[n], args.contexto)
        resolucao = tem_resolucao(depois[n], METAS[n])
        if veredito and not resolucao:
            veredito += " (sem bucket na meta: limite superior grosseiro)"
        resultado["metricas"][n] = {"amostras": amostras, "p50_max_s": p50, "p99_max_s": p99, "meta_s": METAS[n],
                                    "media_s": media, "linhas_ignoradas": depois[n]["ignoradas"],
                                    "bucket_na_meta": resolucao, "veredito": veredito}
    cliente.sort()
    resultado["cliente_ate_terminal_observado_s"] = {
        "amostras": len(cliente), "p50": cliente[len(cliente) // 2], "p99": cliente[min(len(cliente) - 1, int(len(cliente) * 0.99))],
        "nota": "inclui HTTP e o intervalo de polling do script; não é a métrica do RNF04"}
    if args.origem_jaeger:
        time.sleep(3)  # o coletor grava os spans de forma assíncrona
        resultado["atribuicao_por_salto"] = coletar_atribuicao(args.origem_jaeger, traces)
    return resultado


def origem_http(valor):
    """Somente http(s) com host: as origens vêm do Compose local, nunca de `file://` ou outro esquema."""
    partes = urllib.parse.urlsplit(valor)
    if partes.scheme not in ("http", "https") or not partes.hostname:
        raise argparse.ArgumentTypeError("origem precisa ser http(s)://host[:porta]")
    return valor.rstrip("/")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--origem", type=origem_http, default="http://127.0.0.1:8081", help="front office")
    parser.add_argument("--origem-mo", type=origem_http, default="http://127.0.0.1:8082", help="middle office")
    parser.add_argument("--origem-me", type=origem_http, default="http://127.0.0.1:8083", help="matching engine")
    parser.add_argument("--variante", choices=["isolada", "baseline", "candidato"], default="isolada",
                        help="Identifica a variante; a CI confere a flag real dos containers separadamente.")
    parser.add_argument("--ticker", default="ALFA3")
    parser.add_argument("--preco", default="10.00")
    parser.add_argument("--origem-jaeger", type=origem_http, default=None,
                        help="Jaeger (opcional): atribui a latência por salto em até 100 traces da fase medida")
    parser.add_argument("--compradores", default=COMPRADORES_PADRAO,
                        help="contas compradoras em rodízio (teto de 1000 ordens por conta e sessão)")
    parser.add_argument("--amostras", type=int, default=MINIMO_AMOSTRAS_DEDICADO)
    parser.add_argument("--aquecimento", type=int, default=30)
    parser.add_argument("--contexto", choices=["dedicado", "ci-hospedado"], default="dedicado",
                        help="ambiente da execução; em ci-hospedado o veredito é só indicativo")
    args = parser.parse_args(argv)
    try:
        validar_plano(args.amostras, args.aquecimento, [c for c in args.compradores.split(",") if c.strip()], args.contexto)
    except SystemExit as erro:
        parser.error(str(erro))
    print(json.dumps(executar(args), ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
