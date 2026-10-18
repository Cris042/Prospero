#!/usr/bin/env python3
"""RF01–RF04: emite tokens curtos (mercado e ordens por conta), configuração do runner XLSX e verificações contra o
ambiente real; sem exibir a chave."""
import argparse
import concurrent.futures
import datetime
import json
import os
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
           "ordens": ["mercado:leitura", "ordens:enviar", "ordens:leitura"]}
CONTAS = {"CONTA-001", "CONTA-002", "CONTA-003"}
VALIDADE_MAXIMA = 3600


def token(escopo, validade, conta=None):
    if escopo not in ESCOPOS or not 1 <= validade <= VALIDADE_MAXIMA:
        raise SystemExit("Escopo ou validade inválidos.")
    if (escopo == "ordens") != (conta is not None) or (conta is not None and conta not in CONTAS):
        raise SystemExit("Escopo ordens exige --conta CONTA-001..003; demais escopos não aceitam conta.")
    chave = ambiente.ler(ambiente.path).get("FO_JWT_CHAVE")
    if not chave or len(chave) < 32:
        raise SystemExit("FO_JWT_CHAVE ausente; execute python3 scripts/preparar-ambiente.py.")
    agora = int(time.time())
    claims = {"iss": "prospero-local", "aud": "prospero-front-office", "iat": agora,
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


def verificar_risco(origem):
    """SAGA FO→MO real (ADR-012), com carteiras semeadas (volumes novos, como na CI): compras concorrentes que somadas
    excedem o saldo → uma RESERVADA e outra SALDO_INSUFICIENTE; venda sem custódia; quantidade máxima; Market e OCO."""
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

    def decidido(caminho, prazo=20.0):
        limite = time.monotonic() + prazo
        while time.monotonic() < limite:
            corpo = chamar(caminho)
            ordens = corpo.get("ordens", [corpo])
            if all(o["estado"] != "RECEBIDA" for o in ordens):
                return ordens
            time.sleep(0.2)
        raise SystemExit(f"Risco sem resposta em {prazo:.0f} s para {caminho} (MO, Kafka ou tópicos indisponíveis?).")

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
    if falhas:
        raise SystemExit("Falha na verificação de risco: " + "; ".join(falhas))
    print("Risco: reserva concorrente, custódia, quantidade máxima, Market e OCO verificados pela SAGA FO→MO.")


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
    c.add_argument("--validade", type=int, default=900)
    c.add_argument("--perfil", choices=["mercado", "ordens"], default="mercado",
                   help="Menor privilégio: cada workbook recebe só os tokens de que precisa.")
    v = sub.add_parser("verificar-ordens", help="Verifica idempotência concorrente no PostgreSQL real (CONTA-003).")
    v.add_argument("--origem", default="http://127.0.0.1:8081")
    r = sub.add_parser("verificar-risco", help="Verifica reservas pela SAGA FO→MO (CONTA-002, carteira semeada).")
    r.add_argument("--origem", default="http://127.0.0.1:8081")
    d = sub.add_parser("preparar-demo", help="Prepara o namespace principal com dados sintéticos.")
    d.add_argument("--origem", default="http://127.0.0.1:8081")
    args = parser.parse_args()
    if args.comando in {"preparar-demo", "verificar-ordens", "verificar-risco"}:
        partes = urlsplit(args.origem)
        if (partes.scheme != "http" or partes.hostname not in {"127.0.0.1", "localhost"} or partes.username
                or partes.password or partes.path not in {"", "/"} or partes.query or partes.fragment):
            raise SystemExit("Origem deve ser http local (loopback), sem credenciais nem caminho.")
        {"preparar-demo": preparar_demo, "verificar-ordens": verificar_ordens,
         "verificar-risco": verificar_risco}[args.comando](args.origem)
        return
    if args.comando == "token":
        print(token(args.escopo, args.validade, args.conta))
        return
    # ordens-a/ordens-b: mesma origem com contas distintas, para testar isolamento entre contas.
    bearer = lambda valor: {"origem": args.origem, "headers": {"Authorization": "Bearer " + valor}}
    if args.perfil == "mercado":
        config = {"servicos": {"front-office": bearer(token("preparacao", args.validade))}}
    else:
        config = {"servicos": {"ordens-a": bearer(token("ordens", args.validade, "CONTA-002")),
                               "ordens-b": bearer(token("ordens", args.validade, "CONTA-003"))}}
    escrever_config(args.destino, json.dumps(config, indent=2) + "\n")
    print("Configuração do runner gravada; token não exibido.")


if __name__ == "__main__":
    main()
