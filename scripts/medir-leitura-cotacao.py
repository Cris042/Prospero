#!/usr/bin/env python3
"""Medição da leitura de cotações (ADR-022): latência de GET de ativos e livro vista pelo cliente e origem do dado.

Faz `--amostras` leituras sequenciais de cada rota após `--aquecimento`, conta a origem pelo cabeçalho X-Origem-Dados
e imprime JSON com p50/p95/p99/máximo em ms. Só leitura: não cria ordens nem altera carteiras. Compare execuções com
o Redis ligado e desligado (FO_REDIS_HABILITADO) na mesma stack e na mesma janela.
"""
import argparse
import json
import platform
import sys
import time
import urllib.request
from importlib import import_module
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
mercado = import_module("mercado")


def percentil(valores, p):
    ordenados = sorted(valores)
    indice = max(0, min(len(ordenados) - 1, round(p / 100 * len(ordenados) + 0.5) - 1))
    return ordenados[indice]


def medir(url, token, amostras, aquecimento):
    tempos, origens = [], {}
    for i in range(aquecimento + amostras):
        pedido = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
        inicio = time.perf_counter()
        with urllib.request.urlopen(pedido, timeout=5) as resposta:
            resposta.read()
            origem = resposta.headers.get("X-Origem-Dados", "AUSENTE")
        decorrido = (time.perf_counter() - inicio) * 1000
        if i >= aquecimento:
            tempos.append(decorrido)
            origens[origem] = origens.get(origem, 0) + 1
    return {"amostras": amostras, "origens": origens,
            "p50Ms": round(percentil(tempos, 50), 3), "p95Ms": round(percentil(tempos, 95), 3),
            "p99Ms": round(percentil(tempos, 99), 3), "maximoMs": round(max(tempos), 3)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origem", default="http://127.0.0.1:8081")
    parser.add_argument("--variante", required=True, help="rótulo livre, por exemplo redis-ligado")
    parser.add_argument("--amostras", type=int, default=2000)
    parser.add_argument("--aquecimento", type=int, default=200)
    args = parser.parse_args()
    if not args.origem.startswith(("http://127.0.0.1:", "http://localhost:")):
        parser.error("Somente a stack local.")
    token = mercado.token("leitura", 600)
    resultado = {"variante": args.variante, "medidoEm": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
                 "host": {"sistema": platform.platform(), "cpus": __import__("os").cpu_count()},
                 "ativos": medir(args.origem + "/api/v1/ativos", token, args.amostras, args.aquecimento),
                 "livro": medir(args.origem + "/api/v1/ativos/ALFA3/livro", token, args.amostras, args.aquecimento)}
    print(json.dumps(resultado, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
