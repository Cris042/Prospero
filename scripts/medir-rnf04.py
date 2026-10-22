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
import os
import platform
import subprocess
import sys
import time
import urllib.request
import uuid
from importlib import import_module
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
mercado = import_module("mercado")

METAS = {"prospero_ordens_boleta_negocio_seconds": 0.050, "prospero_matching_comando_seconds": 0.001}


def buckets(origem, nome):
    """Contagens cumulativas por limite `le` do histograma Prometheus do serviço."""
    with urllib.request.urlopen(origem + "/q/metrics", timeout=10) as resposta:
        texto = resposta.read().decode()
    contagens = {}
    for linha in texto.splitlines():
        if linha.startswith(nome + "_bucket{"):
            le = linha.split('le="', 1)[1].split('"', 1)[0]
            contagens[float("inf") if le == "+Inf" else float(le)] = float(linha.rsplit(" ", 1)[1])
    return contagens


def percentil(antes, depois, q):
    """Menor limite de bucket que cobre a fração q das amostras novas; None sem amostras."""
    limites = sorted(depois)
    total = depois.get(float("inf"), 0) - antes.get(float("inf"), 0)
    if total <= 0:
        return None, 0
    for le in limites:
        if depois[le] - antes.get(le, 0) >= q * total:
            return le, int(total)
    return float("inf"), int(total)


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


def executar(args):
    mercado.preparar_carteira(args.origem_mo, "CONTA-002", "1000000.00", {}, prazo=60)
    mercado.preparar_carteira(args.origem_mo, "CONTA-003", "0.00", {args.ticker: args.amostras + args.aquecimento}, prazo=60)
    vendedor = mercado.token("ordens", 900, "CONTA-003")
    comprador = mercado.token("ordens", 900, "CONTA-002")

    def enviar(tok, corpo):
        return mercado.http("POST", args.origem + "/api/v1/ordens", tok, corpo,
                            {"X-Idempotency-Key": "rnf04-" + uuid.uuid4().hex})

    total = args.amostras + args.aquecimento
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
    resultado = {"ambiente": ambiente(), "plano": vars(args), "metricas": {}}
    prefixo = "indicativo: " if args.contexto == "ci-hospedado" else ""
    for n in nomes:
        p50, amostras = percentil(antes[n], depois[n], 0.50)
        p99, _ = percentil(antes[n], depois[n], 0.99)
        resultado["metricas"][n] = {"amostras": amostras, "p50_max_s": p50, "p99_max_s": p99, "meta_s": METAS[n],
                                    "veredito": None if p99 is None else prefixo + ("cumprido" if p99 <= METAS[n] else "não cumprido")}
    cliente.sort()
    resultado["cliente_ate_terminal_observado_s"] = {
        "amostras": len(cliente), "p50": cliente[len(cliente) // 2], "p99": cliente[min(len(cliente) - 1, int(len(cliente) * 0.99))],
        "nota": "inclui HTTP e o intervalo de polling do script; não é a métrica do RNF04"}
    return resultado


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--origem", default="http://127.0.0.1:8081", help="front office")
    parser.add_argument("--origem-mo", default="http://127.0.0.1:8082", help="middle office")
    parser.add_argument("--origem-me", default="http://127.0.0.1:8083", help="matching engine")
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
