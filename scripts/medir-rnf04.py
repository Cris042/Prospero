#!/usr/bin/env python3
"""Primeira medição do RNF04 (RF05d): boleta → negócio e núcleo do matching, sob carga local documentada.

Contraparte já em repouso (CONTA-003) e agressores Limit IOC de 1 unidade (CONTA-002) enviados em sequência, cada um
só depois do terminal do anterior: mede processamento, não espera por contraparte nem fila. Os percentis saem da
diferença dos histogramas Prometheus antes/depois da fase medida (aquecimento excluído) e são limites superiores do
bucket. O veredito compara o p99 com a meta; em ambiente compartilhado (`--contexto ci-hospedado`) é só indicativo.
Atenção: redefine as carteiras de teste CONTA-002/003 (preparação local), apagando o estado delas.
"""
import argparse
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


def classificar(p99, meta, contexto="dedicado"):
    """Somente limite superior estritamente abaixo da meta comprova a desigualdade exigida."""
    if p99 is None:
        return None
    prefixo = "indicativo: " if contexto == "ci-hospedado" else ""
    return prefixo + ("cumprido" if p99 < meta else "não cumprido")


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
    total = args.amostras + args.aquecimento
    vendedor = mercado.token("ordens", 900, "CONTA-003")
    comprador = mercado.token("ordens", 900, "CONTA-002")
    orcamento = {"compradorExistentes": verificar_orcamento(args.origem, comprador, total),
                 "vendedorExistentes": verificar_orcamento(args.origem, vendedor, 1),
                 "compradorNecessarias": total, "vendedorNecessarias": 1, "limite": 1000}
    mercado.preparar_carteira(args.origem_mo, "CONTA-002", "1000000.00", {}, prazo=60)
    mercado.preparar_carteira(args.origem_mo, "CONTA-003", "0.00", {args.ticker: total}, prazo=60)

    def enviar(tok, corpo):
        return mercado.http("POST", args.origem + "/api/v1/ordens", tok, corpo,
                            {"X-Idempotency-Key": "rnf04-" + uuid.uuid4().hex})

    oferta = enviar(vendedor, {"ticker": args.ticker, "lado": "VENDA", "tipo": "LIMIT", "validade": "DIA",
                               "quantidade": total, "precoLimite": args.preco})
    mercado.aguardar_campos(args.origem, vendedor, f"/api/v1/ordens/{oferta['id']}", {"estado": "ABERTA"})
    nomes = list(METAS)
    origens = {nomes[0]: args.origem, nomes[1]: args.origem_me}
    antes, cliente = None, []
    for i in range(total):
        if i == args.aquecimento:
            antes = {n: buckets(origens[n], n) for n in nomes}
        inicio = time.perf_counter()
        ordem = enviar(comprador, {"ticker": args.ticker, "lado": "COMPRA", "tipo": "LIMIT", "validade": "IOC",
                                   "quantidade": 1, "precoLimite": args.preco})
        mercado.aguardar_campos(args.origem, comprador, f"/api/v1/ordens/{ordem['id']}", {"estado": "EXECUTADA"})
        if i >= args.aquecimento:
            cliente.append(time.perf_counter() - inicio)
    depois = {n: buckets(origens[n], n) for n in nomes}
    resultado = {"ambiente": ambiente(), "plano": vars(args), "orcamentoAntes": orcamento, "metricas": {}}
    for n in nomes:
        p50, amostras = percentil(antes[n], depois[n], 0.50)
        p99, _ = percentil(antes[n], depois[n], 0.99)
        media = media_independente(antes[n], depois[n])
        resultado["metricas"][n] = {"amostras": amostras, "p50_max_s": p50, "p99_max_s": p99, "meta_s": METAS[n],
                                    "media_s": media, "linhas_ignoradas": depois[n]["ignoradas"],
                                    "veredito": classificar(p99, METAS[n], args.contexto)}
    cliente.sort()
    resultado["cliente_ate_terminal_observado_s"] = {
        "amostras": len(cliente), "p50": cliente[len(cliente) // 2], "p99": cliente[min(len(cliente) - 1, int(len(cliente) * 0.99))],
        "nota": "inclui HTTP e o intervalo de polling do script; não é a métrica do RNF04"}
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
    parser.add_argument("--amostras", type=int, default=200)
    parser.add_argument("--aquecimento", type=int, default=30)
    parser.add_argument("--contexto", choices=["dedicado", "ci-hospedado"], default="dedicado",
                        help="ambiente da execução; em ci-hospedado o veredito é só indicativo")
    args = parser.parse_args(argv)
    if not 1 <= args.amostras <= 800 or not 0 <= args.aquecimento <= 150:
        parser.error("Amostras 1–800 e aquecimento 0–150: o teto de 1000 ordens por conta e sessão vale aqui.")
    print(json.dumps(executar(args), ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
