#!/usr/bin/env python3
"""RF01: emite token curto de preparação/leitura e configuração do runner XLSX; sem exibir a chave."""
import argparse
import datetime
import json
import os
import urllib.request
from zoneinfo import ZoneInfo
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
from importlib import import_module

ambiente = import_module("preparar-ambiente")
ESCOPOS = {"leitura": ["mercado:leitura"], "preparacao": ["mercado:leitura", "mercado:preparacao"]}
VALIDADE_MAXIMA = 3600


def token(escopo, validade):
    if escopo not in ESCOPOS or not 1 <= validade <= VALIDADE_MAXIMA:
        raise SystemExit("Escopo ou validade inválidos.")
    chave = ambiente.ler(ambiente.path).get("FO_JWT_CHAVE")
    if not chave or len(chave) < 32:
        raise SystemExit("FO_JWT_CHAVE ausente; execute python3 scripts/preparar-ambiente.py.")
    agora = int(time.time())
    return ambiente.jwt({"iss": "prospero-local", "aud": "prospero-front-office", "iat": agora,
                         "exp": agora + validade, "groups": ESCOPOS[escopo]}, chave)


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


def escrever_privado(destino, conteudo):
    # Arquivo legível pelo UID do container via bind individual; criado sem seguir symlink.
    fd = os.open(destino, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o644)
    with os.fdopen(fd, "w") as stream:
        stream.write(conteudo)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="comando", required=True)
    t = sub.add_parser("token", help="Imprime token curto no stdout.")
    t.add_argument("--escopo", choices=sorted(ESCOPOS), default="leitura")
    t.add_argument("--validade", type=int, default=900)
    c = sub.add_parser("config-runner", help="Grava configuração do runner com token de preparação.")
    c.add_argument("destino")
    c.add_argument("--origem", default="http://front-office:8080")
    c.add_argument("--validade", type=int, default=900)
    d = sub.add_parser("preparar-demo", help="Prepara o namespace principal com dados sintéticos.")
    d.add_argument("--origem", default="http://127.0.0.1:8081")
    args = parser.parse_args()
    if args.comando == "preparar-demo":
        if not args.origem.startswith(("http://127.0.0.1:", "http://localhost:")):
            raise SystemExit("Origem deve ser local (loopback).")
        preparar_demo(args.origem)
        return
    if args.comando == "token":
        print(token(args.escopo, args.validade))
        return
    config = {"servicos": {"front-office": {"origem": args.origem, "headers": {
        "Authorization": "Bearer " + token("preparacao", args.validade)}}}}
    escrever_privado(args.destino, json.dumps(config, indent=2) + "\n")
    print("Configuração do runner gravada; token não exibido.")


if __name__ == "__main__":
    main()
