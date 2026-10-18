#!/usr/bin/env python3
"""RF01–RF04: emite tokens curtos (mercado e ordens por conta), configuração do runner XLSX e verificações contra o
ambiente real; sem exibir a chave."""
import argparse
import concurrent.futures
import datetime
import json
import os
import queue
import re
import subprocess
import threading
import urllib.error
import urllib.request
import uuid
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
from importlib import import_module

ambiente = import_module("preparar-ambiente")
ESCOPOS = {"leitura": ["mercado:leitura"], "preparacao": ["mercado:leitura", "mercado:preparacao"],
           "ordens": ["mercado:leitura", "ordens:enviar", "ordens:leitura"],
           "risco": ["risco:leitura"], "risco-preparacao": ["risco:preparacao"]}
# Escopos do MO usam audiência própria (RF04b): token do FO não vale no MO e vice-versa.
AUDIENCIAS = {"risco": "prospero-middle-office", "risco-preparacao": "prospero-middle-office"}
POR_CONTA = {"ordens", "risco"}
ATIVOS = ["ALFA3", "BETA3", "GAMA3", "DELTA3", "OMEGA3"]
CONTAS = {"CONTA-001", "CONTA-002", "CONTA-003"}
VALIDADE_MAXIMA = 3600


def token(escopo, validade, conta=None):
    if escopo not in ESCOPOS or not 1 <= validade <= VALIDADE_MAXIMA:
        raise SystemExit("Escopo ou validade inválidos.")
    if (escopo in POR_CONTA) != (conta is not None) or (conta is not None and conta not in CONTAS):
        raise SystemExit("Escopos ordens e risco exigem --conta CONTA-001..003; demais escopos não aceitam conta.")
    chave = ambiente.ler(ambiente.path).get("FO_JWT_CHAVE")
    if not chave or len(chave) < 32:
        raise SystemExit("FO_JWT_CHAVE ausente; execute python3 scripts/preparar-ambiente.py.")
    agora = int(time.time())
    claims = {"iss": "prospero-local", "aud": AUDIENCIAS.get(escopo, "prospero-front-office"), "iat": agora,
              "exp": agora + validade, "groups": ESCOPOS[escopo]}
    if conta:
        claims["sub"] = conta
    return ambiente.jwt(claims, chave)


DEMO = {
    "ALFA3": ([("COMPRA", "10.30", 100), ("COMPRA", "10.29", 300), ("COMPRA", "10.28", 200), ("COMPRA", "10.25", 500),
               ("COMPRA", "10.20", 800), ("COMPRA", "10.10", 1000), ("VENDA", "10.36", 200), ("VENDA", "10.38", 400),
               ("VENDA", "10.40", 300), ("VENDA", "10.45", 700), ("VENDA", "10.50", 900)],
              [("10.20", 100), ("10.35", 300)]),
    "BETA3": ([("COMPRA", "19.80", 50), ("VENDA", "19.95", 120)], [("19.90", 500)]),
    "GAMA3": ([("VENDA", "30.50", 40)], []),
    "DELTA3": ([], []),
}


def preparar_demo(origem):
    # Namespace principal (UUID nulo), somente via API local; OMEGA3 permanece sem dados.
    token_prep = token("preparacao", 120)
    sessao = datetime.datetime.now(ZoneInfo("America/Sao_Paulo")).date().isoformat()
    for ticker, (ofertas, negocios) in DEMO.items():
        corpo = json.dumps({"sessao": sessao,
                            "ofertas": [{"lado": l, "preco": p, "quantidade": q} for l, p, q in ofertas],
                            "negocios": [{"preco": p, "quantidade": q} for p, q in negocios]}).encode()
        pedido = urllib.request.Request(f"{origem}/api/v1/preparacoes-mercado/00000000-0000-0000-0000-000000000000/ativos/{ticker}",
                                        data=corpo, method="PUT", headers={
                                            "Authorization": "Bearer " + token_prep, "Content-Type": "application/json"})
        with urllib.request.urlopen(pedido, timeout=5) as resposta:
            if resposta.status != 200:
                raise SystemExit(f"Falha ao preparar {ticker}: HTTP {resposta.status}")
    print("Namespace principal preparado com dados sintéticos de demonstração.")


def verificar_ordens(origem):
    """Concorrência real no PostgreSQL (ADR-010): mesma chave → uma ordem; divergência → um 202 e demais 409."""
    tok = token("ordens", 120, "CONTA-003")

    def enviar(chave, corpo, caminho="/api/v1/ordens"):
        pedido = urllib.request.Request(origem + caminho, data=json.dumps(corpo).encode(), method="POST", headers={
            "Authorization": "Bearer " + tok, "Content-Type": "application/json", "X-Idempotency-Key": chave})
        try:
            with urllib.request.urlopen(pedido, timeout=10) as resposta:
                return resposta.status, json.load(resposta)
        except urllib.error.HTTPError as erro:
            return erro.code, None

    def limit(preco):
        return {"ticker": "GAMA3", "lado": "VENDA", "tipo": "LIMIT", "validade": "FOK", "quantidade": 3,
                "precoLimite": preco}

    with concurrent.futures.ThreadPoolExecutor(20) as executor:
        chave = "conc-" + uuid.uuid4().hex[:16]
        iguais = list(executor.map(lambda _: enviar(chave, limit("30.00")), range(20)))
        chave = "conc-" + uuid.uuid4().hex[:16]
        divergentes = list(executor.map(lambda i: enviar(chave, limit(f"30.{i:02d}")), range(10)))
        chave = "conc-" + uuid.uuid4().hex[:16]
        oco = {"ticker": "DELTA3", "lado": "VENDA", "quantidade": 5, "validade": "DIA",
               "takeProfit": {"precoLimite": "42.00"}, "stopLoss": {"tipo": "STOP", "precoGatilho": "38.00"}}
        grupos = list(executor.map(lambda _: enviar(chave, oco, "/api/v1/ordens-oco"), range(10)))
    falhas = []
    if {s for s, _ in iguais} != {202} or len({c["id"] for _, c in iguais}) != 1:
        falhas.append("mesma chave e mesmo corpo deveriam gerar uma única ordem")
    if sorted(s for s, _ in divergentes).count(202) != 1 or {s for s, _ in divergentes} - {202, 409}:
        falhas.append("corpos divergentes com a mesma chave deveriam gerar um 202 e 409 nos demais")
    if {s for s, _ in grupos} != {202} or len({c["grupoOco"] for _, c in grupos}) != 1:
        falhas.append("OCO concorrente com a mesma chave deveria gerar um único grupo")
    if falhas:
        raise SystemExit("Falha na verificação de ordens: " + "; ".join(falhas))
    print("Ordens: concorrência idempotente verificada (20 iguais, 10 divergentes, 10 OCO).")


def http(metodo, url, tok, corpo=None, cabecalhos=None):
    extra = {"Authorization": "Bearer " + tok, **(cabecalhos or {})}
    if corpo is not None:
        extra["Content-Type"] = "application/json"
    pedido = urllib.request.Request(url, method=metodo, data=None if corpo is None else json.dumps(corpo).encode(),
                                    headers=extra)
    with urllib.request.urlopen(pedido, timeout=10) as resposta:
        return json.load(resposta)


def preparar_carteira(origem_mo, conta, saldo, custodia):
    """RF04b: redefine a carteira de uma conta de teste pela API local do MO (apaga as reservas dela)."""
    return http("PUT", f"{origem_mo}/api/v1/preparacoes-risco/contas/{conta}", token("risco-preparacao", 60),
                {"saldo": saldo, "custodia": custodia})


def carteira(origem_mo, conta):
    return http("GET", f"{origem_mo}/api/v1/risco/carteira", token("risco", 60, conta))


def aguardar_decisao(origem, tok, caminho, prazo=20.0):
    limite = time.monotonic() + prazo
    while time.monotonic() < limite:
        corpo = http("GET", origem + caminho, tok)
        ordens = corpo.get("ordens", [corpo])
        if all(o["estado"] != "RECEBIDA" for o in ordens):
            return ordens
        time.sleep(0.2)
    raise SystemExit(f"Risco sem resposta em {prazo:.0f} s para {caminho} (MO, Kafka ou tópicos indisponíveis?).")


def verificar_risco(origem, origem_mo):
    """SAGA FO→MO real (ADR-012), repetível: prepara CONTA-002 (R$ 1.000.000,00 e 1.000 ações/ativo); compras
    concorrentes que somadas excedem o saldo → uma RESERVADA e outra SALDO_INSUFICIENTE; venda sem custódia; quantidade
    máxima; Market e OCO; ao fim, a carteira do MO reflete exatamente as reservas."""
    preparar_carteira(origem_mo, "CONTA-002", "1000000.00", {t: 1000 for t in ATIVOS})
    tok = token("ordens", 120, "CONTA-002")

    def chamar(caminho, corpo=None):
        cabecalhos = {"Authorization": "Bearer " + tok}
        if corpo is not None:
            cabecalhos.update({"Content-Type": "application/json", "X-Idempotency-Key": "risco-" + uuid.uuid4().hex[:16]})
        pedido = urllib.request.Request(origem + caminho, method="POST" if corpo is not None else "GET",
                                        data=None if corpo is None else json.dumps(corpo).encode(), headers=cabecalhos)
        with urllib.request.urlopen(pedido, timeout=10) as resposta:
            if resposta.status not in (200, 202):
                raise SystemExit(f"HTTP {resposta.status} em {caminho}")
            return json.load(resposta)

    def decidido(caminho):
        return aguardar_decisao(origem, tok, caminho)

    def limit(ticker, lado, quantidade, preco):
        return {"ticker": ticker, "lado": lado, "tipo": "LIMIT", "validade": "DIA", "quantidade": quantidade,
                "precoLimite": preco}

    falhas = []
    # 2 × 60.000 × 10,00 = 1.200.000,00 sobre 1.000.000,00 semeados: a linha de controle admite só uma.
    with concurrent.futures.ThreadPoolExecutor(2) as executor:
        ids = list(executor.map(lambda _: chamar("/api/v1/ordens", limit("ALFA3", "COMPRA", 60000, "10.00"))["id"],
                                range(2)))
    estados = sorted((o["estado"], o["motivoRejeicao"]) for i in ids for o in decidido(f"/api/v1/ordens/{i}"))
    if estados != [("REJEITADA", "SALDO_INSUFICIENTE"), ("RESERVADA", None)]:
        falhas.append(f"compras concorrentes acima do saldo: esperado uma reserva e uma rejeição, obtido {estados}")
    casos = [
        ("venda acima da custódia", "/api/v1/ordens", limit("GAMA3", "VENDA", 1001, "30.00"),
         [("REJEITADA", "CUSTODIA_INSUFICIENTE")]),
        ("quantidade máxima por ordem", "/api/v1/ordens", limit("BETA3", "COMPRA", 100001, "20.00"),
         [("REJEITADA", "QUANTIDADE_MAXIMA_EXCEDIDA")]),
        ("compra Market pelo teto do túnel", "/api/v1/ordens",
         {"ticker": "BETA3", "lado": "COMPRA", "tipo": "MARKET", "validade": "IOC", "quantidade": 10},
         [("RESERVADA", None)]),
        ("OCO com reserva única", "/api/v1/ordens-oco",
         {"ticker": "DELTA3", "lado": "VENDA", "quantidade": 5, "validade": "DIA",
          "takeProfit": {"precoLimite": "42.00"}, "stopLoss": {"tipo": "STOP", "precoGatilho": "38.00"}},
         [("RESERVADA", None), ("RESERVADA", None)]),
    ]
    for nome, caminho, corpo, esperado in casos:
        criado = chamar(caminho, corpo)
        alvo = f"/api/v1/ordens-oco/{criado['grupoOco']}" if "grupoOco" in criado and caminho.endswith("oco") \
            else f"/api/v1/ordens/{criado['id']}"
        obtido = [(o["estado"], o["motivoRejeicao"]) for o in decidido(alvo)]
        if obtido != esperado:
            falhas.append(f"{nome}: esperado {esperado}, obtido {obtido}")
    # 600.000,00 (compra concorrente vencedora) + 10 × teto(20,00) = 220,00 (Market); OCO de venda reserva 5 DELTA3.
    final = carteira(origem_mo, "CONTA-002")
    delta = next(c for c in final["custodias"] if c["ticker"] == "DELTA3")
    if (final["saldoReservado"], final["saldoDisponivel"], delta["reservado"]) != ("600220.00", "399780.00", 5):
        falhas.append(f"carteira divergente das reservas: {final}")
    if falhas:
        raise SystemExit("Falha na verificação de risco: " + "; ".join(falhas))
    print("Risco: reserva concorrente, custódia, quantidade máxima, Market, OCO e carteira do MO verificados.")


def compose(projeto):
    base = ["docker", "compose"]
    return base + ["-p", projeto, "-f", "docker-compose.yml", "-f", "docker-compose.ci.yml"] if projeto else base


def verificar_concorrencia(origem_mo, projeto):
    """Prova SQL com duas conexões reais no PostgreSQL do MO (HLD-003): ler-e-depois-gravar concede 140 sobre 100;
    o UPDATE condicional usado pelo MO concede só uma reserva de 70 e a segunda reavalia após o commit da primeira."""
    psql = compose(projeto) + ["exec", "-T", "postgres-middle-office", "psql", "-X", "-U", "prospero_mo", "-d",
                               "prospero_mo", "-v", "ON_ERROR_STOP=1", "-A", "-t"]

    class Sessao:
        def __init__(self):
            self.p = subprocess.Popen(psql, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                      text=True, bufsize=1)
            self.linhas = queue.Queue()
            threading.Thread(target=lambda: [self.linhas.put(l.strip()) for l in self.p.stdout], daemon=True).start()

        def enviar(self, sql):
            self.p.stdin.write(sql + "\n")
            self.p.stdin.flush()

        def ler(self, prazo=10.0):
            return self.linhas.get(timeout=prazo)

        def fechar(self):
            self.p.stdin.close()
            self.p.wait(timeout=10)

    def unico(sql):
        return subprocess.check_output(psql + ["-c", sql], text=True).strip()

    conta = "CONTA-003"
    filtro = f"conta = '{conta}'"
    # RED: sem proteção, as duas sessões leem 100, ambas concedem 70 e gravam o valor calculado na leitura.
    preparar_carteira(origem_mo, conta, "100.00", {})
    a, b = Sessao(), Sessao()
    concedidas = 0
    for s in (a, b):
        s.enviar(f"BEGIN; SELECT disponivel FROM conta_risco WHERE {filtro};")
    for s in (a, b):
        if s.ler() != "BEGIN" or s.ler() != "100.00":
            raise SystemExit("Concorrência SQL: leitura inicial inesperada.")
        concedidas += 1
    for s in (a, b):
        s.enviar(f"UPDATE conta_risco SET disponivel = 30.00, reservado = 70.00 WHERE {filtro}; COMMIT;")
        s.ler()
        s.ler()
        s.fechar()
    if concedidas != 2:
        raise SystemExit("Concorrência SQL: anomalia de ler-e-depois-gravar não reproduzida.")
    # GREEN: UPDATE condicional (o mesmo de RepositorioRiscoJpa.reservarCaixa).
    preparar_carteira(origem_mo, conta, "100.00", {})
    reservar = (f"UPDATE conta_risco SET disponivel = disponivel - 70, reservado = reservado + 70 "
                f"WHERE {filtro} AND disponivel >= 70;")
    a, b = Sessao(), Sessao()
    a.enviar("BEGIN; " + reservar)
    if (a.ler(), a.ler()) != ("BEGIN", "UPDATE 1"):
        raise SystemExit("Concorrência SQL: primeira reserva não aplicada.")
    b.enviar("BEGIN; SET LOCAL lock_timeout = '15s'; " + reservar)
    b.ler()
    b.ler()
    limite = time.monotonic() + 10
    while unico("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                "AND query LIKE 'UPDATE conta_risco%'") != "1":
        if time.monotonic() > limite:
            raise SystemExit("Concorrência SQL: a segunda sessão não esperou o lock da linha de controle.")
        time.sleep(0.2)
    a.enviar("COMMIT;")
    a.ler()
    segunda = b.ler(15)
    b.enviar("COMMIT;")
    b.ler()
    a.fechar()
    b.fechar()
    estado = unico(f"SELECT disponivel || '|' || reservado FROM conta_risco WHERE {filtro}")
    preparar_carteira(origem_mo, conta, "1000000.00", {t: 1000 for t in ATIVOS})
    if segunda != "UPDATE 0" or estado != "30.00|70.00":
        raise SystemExit(f"Concorrência SQL: esperado UPDATE 0 e 30.00|70.00, obtido {segunda} e {estado}.")
    print("Concorrência SQL: ler-e-depois-gravar concede 140 sobre 100; UPDATE condicional concede uma reserva de 70.")


def verificar_recuperacao(origem, origem_mo, projeto):
    """MO parado: ordem fica RECEBIDA e é decidida uma vez ao voltar; comando reentregue e mensagem inválida no tópico
    não criam reserva nem travam o consumo (ADR-012)."""
    base = compose(projeto)
    conta = "CONTA-003"
    preparar_carteira(origem_mo, conta, "1000.00", {})
    tok = token("ordens", 300, conta)
    ordem = {"ticker": "ALFA3", "lado": "COMPRA", "tipo": "LIMIT", "validade": "DIA", "quantidade": 10,
             "precoLimite": "10.00"}
    subprocess.run(base + ["stop", "middle-office"], check=True, capture_output=True)
    try:
        criada = http("POST", origem + "/api/v1/ordens", tok, ordem,
                      {"X-Idempotency-Key": "recup-" + uuid.uuid4().hex[:16]})
        time.sleep(3)
        if http("GET", f"{origem}/api/v1/ordens/{criada['id']}", tok)["estado"] != "RECEBIDA":
            raise SystemExit("Recuperação: ordem decidida com o MO parado.")
    finally:
        subprocess.run(base + ["start", "middle-office"], check=True, capture_output=True)
    if projeto:
        # Porta publicada dinâmica (CI) pode mudar quando o contêiner reinicia.
        origem_mo = "http://" + subprocess.check_output(base + ["port", "middle-office", "8080"], text=True).strip()
    limite = time.monotonic() + 180
    while True:
        try:
            if http("GET", origem_mo + "/q/health/ready", "x").get("status") == "UP":
                break
        except (urllib.error.URLError, ConnectionError, ValueError):
            pass
        if time.monotonic() > limite:
            raise SystemExit("Recuperação: MO não voltou a ficar pronto.")
        time.sleep(1)
    [decidida] = aguardar_decisao(origem, tok, f"/api/v1/ordens/{criada['id']}", 60)
    if (decidida["estado"], carteira(origem_mo, conta)["saldoReservado"]) != ("RESERVADA", "100.00"):
        raise SystemExit(f"Recuperação: esperado RESERVADA com 100.00 reservado, obtido {decidida}.")
    # Reentrega do mesmo comando (cópia da outbox do FO) e mensagem inválida, seguidas de uma ordem nova.
    payload = subprocess.check_output(base + [
        "exec", "-T", "postgres-front-office", "psql", "-X", "-A", "-t", "-U", "prospero_fo", "-d", "prospero_fo",
        "-c", f"SELECT payload FROM outbox WHERE payload LIKE '%\"correlacao\":\"{criada['id']}\"%'"],
        text=True).strip()
    mensagens = f"{conta}|{payload}\n{conta}|{{nao-e-json\n"
    subprocess.run(base + ["exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-producer.sh", "--bootstrap-server",
                           "localhost:9092", "--topic", "risco.comandos.v1", "--property", "parse.key=true",
                           "--property", "key.separator=|"], input=mensagens, text=True, check=True,
                   capture_output=True)
    nova = http("POST", origem + "/api/v1/ordens", tok, {**ordem, "quantidade": 5},
                {"X-Idempotency-Key": "recup-" + uuid.uuid4().hex[:16]})
    aguardar_decisao(origem, tok, f"/api/v1/ordens/{nova['id']}", 30)
    reservado = carteira(origem_mo, conta)["saldoReservado"]
    preparar_carteira(origem_mo, conta, "1000000.00", {t: 1000 for t in ATIVOS})
    if reservado != "150.00":
        raise SystemExit(f"Recuperação: reentrega alterou a reserva (esperado 150.00, obtido {reservado}).")
    print("Recuperação: MO parado mantém RECEBIDA e decide uma vez; reentrega e mensagem inválida sem efeito.")


def escrever_config(destino, conteudo):
    # 0644 para o UID do container ler via bind individual: o destino deve estar em diretório privado (0700).
    fd = os.open(destino, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o644)
    with os.fdopen(fd, "w") as stream:
        stream.write(conteudo)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="comando", required=True)
    t = sub.add_parser("token", help="Imprime token curto no stdout.")
    t.add_argument("--escopo", choices=sorted(ESCOPOS), default="leitura")
    t.add_argument("--validade", type=int, default=900)
    t.add_argument("--conta", choices=sorted(CONTAS))
    c = sub.add_parser("config-runner", help="Grava configuração do runner com token de preparação.")
    c.add_argument("destino")
    c.add_argument("--origem", default="http://front-office:8080")
    c.add_argument("--origem-mo", default="http://middle-office:8080")
    c.add_argument("--validade", type=int, default=900)
    c.add_argument("--perfil", choices=["mercado", "ordens", "risco"], default="mercado",
                   help="Menor privilégio: cada workbook recebe só os tokens de que precisa.")
    v = sub.add_parser("verificar-ordens", help="Verifica idempotência concorrente no PostgreSQL real (CONTA-003).")
    v.add_argument("--origem", default="http://127.0.0.1:8081")
    r = sub.add_parser("verificar-risco", help="Verifica reservas pela SAGA FO→MO (prepara CONTA-002).")
    r.add_argument("--origem", default="http://127.0.0.1:8081")
    r.add_argument("--origem-mo", default="http://127.0.0.1:8082")
    q = sub.add_parser("verificar-concorrencia", help="Prova SQL com duas conexões no PostgreSQL do MO (CONTA-003).")
    q.add_argument("--origem-mo", default="http://127.0.0.1:8082")
    q.add_argument("--projeto", help="Projeto Compose de CI (usa docker-compose.ci.yml).")
    e = sub.add_parser("verificar-recuperacao", help="Para o MO, verifica RECEBIDA, reentrega e mensagem inválida.")
    e.add_argument("--origem", default="http://127.0.0.1:8081")
    e.add_argument("--origem-mo", default="http://127.0.0.1:8082")
    e.add_argument("--projeto", help="Projeto Compose de CI (usa docker-compose.ci.yml).")
    d = sub.add_parser("preparar-demo", help="Prepara o namespace principal com dados sintéticos.")
    d.add_argument("--origem", default="http://127.0.0.1:8081")
    args = parser.parse_args()
    if args.comando in {"preparar-demo", "verificar-ordens", "verificar-risco", "verificar-concorrencia",
                        "verificar-recuperacao"}:
        for origem in (getattr(args, "origem", None), getattr(args, "origem_mo", None)):
            partes = urlsplit(origem) if origem else None
            if partes and (partes.scheme != "http" or partes.hostname not in {"127.0.0.1", "localhost"}
                           or partes.username or partes.password or partes.path not in {"", "/"} or partes.query
                           or partes.fragment):
                raise SystemExit("Origem deve ser http local (loopback), sem credenciais nem caminho.")
        if getattr(args, "projeto", None) and not re.fullmatch(r"prospero-ci-\d+-\d+", args.projeto):
            raise SystemExit("Projeto Compose de CI inválido.")
        match args.comando:
            case "preparar-demo":
                preparar_demo(args.origem)
            case "verificar-ordens":
                verificar_ordens(args.origem)
            case "verificar-risco":
                verificar_risco(args.origem, args.origem_mo)
            case "verificar-concorrencia":
                verificar_concorrencia(args.origem_mo, args.projeto)
            case "verificar-recuperacao":
                verificar_recuperacao(args.origem, args.origem_mo, args.projeto)
        return
    if args.comando == "token":
        print(token(args.escopo, args.validade, args.conta))
        return
    risco = lambda valor: {"origem": args.origem_mo, "headers": {"Authorization": "Bearer " + valor}}
    # ordens-a/ordens-b: mesma origem com contas distintas, para testar isolamento entre contas.
    bearer = lambda valor: {"origem": args.origem, "headers": {"Authorization": "Bearer " + valor}}
    if args.perfil == "mercado":
        config = {"servicos": {"front-office": bearer(token("preparacao", args.validade))}}
    elif args.perfil == "ordens":
        config = {"servicos": {"ordens-a": bearer(token("ordens", args.validade, "CONTA-002")),
                               "ordens-b": bearer(token("ordens", args.validade, "CONTA-003"))}}
    else:
        # RF04b: ordens e carteira da CONTA-002, carteira da CONTA-003 (isolamento) e preparação de carteiras.
        config = {"servicos": {"ordens-a": bearer(token("ordens", args.validade, "CONTA-002")),
                               "risco-a": risco(token("risco", args.validade, "CONTA-002")),
                               "risco-b": risco(token("risco", args.validade, "CONTA-003")),
                               "preparacao-risco": risco(token("risco-preparacao", args.validade))}}
    escrever_config(args.destino, json.dumps(config, indent=2) + "\n")
    print("Configuração do runner gravada; token não exibido.")


if __name__ == "__main__":
    main()
