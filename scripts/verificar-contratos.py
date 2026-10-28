#!/usr/bin/env python3
"""Confere o manifesto SHA-256 das fixtures de contrato compartilhadas entre os serviços (contratos/)."""
from pathlib import Path
import hashlib
import re
import sys

RAIZ = Path(__file__).resolve().parents[1] / "contratos"
LINHA = re.compile(r"^([0-9a-f]{64})  (\S+)$")


def verificar(base: Path) -> list[str]:
    erros = []
    manifesto = base / "MANIFESTO.sha256"
    if not manifesto.is_file():
        return [f"{base.name}: MANIFESTO.sha256 ausente"]
    listados = {}
    for numero, linha in enumerate(manifesto.read_text(encoding="utf-8").splitlines(), 1):
        encontrada = LINHA.match(linha)
        if not encontrada:
            erros.append(f"{base.name}: linha {numero} fora do formato 'sha256  caminho'")
            continue
        caminho = encontrada.group(2)
        if caminho.startswith("/") or ".." in Path(caminho).parts or caminho in listados:
            erros.append(f"{base.name}: caminho inválido ou repetido: {caminho}")
            continue
        listados[caminho] = encontrada.group(1)
    existentes = {p.relative_to(base).as_posix(): p for p in base.rglob("*") if p.is_file()}
    existentes.pop("MANIFESTO.sha256", None)
    for caminho in sorted(set(existentes) - set(listados)):
        erros.append(f"{base.name}: arquivo fora do manifesto: {caminho}")
    for caminho in sorted(set(listados) - set(existentes)):
        erros.append(f"{base.name}: arquivo do manifesto ausente: {caminho}")
    for caminho in sorted(set(listados) & set(existentes)):
        if hashlib.sha256(existentes[caminho].read_bytes()).hexdigest() != listados[caminho]:
            erros.append(f"{base.name}: hash divergente: {caminho}")
    return erros


def main() -> int:
    versoes = sorted(p for p in RAIZ.iterdir() if p.is_dir()) if RAIZ.is_dir() else []
    if not versoes:
        print("ERRO: nenhuma versão de contrato em contratos/")
        return 1
    erros = [erro for versao in versoes for erro in verificar(versao)]
    for erro in erros:
        print("ERRO:", erro)
    if erros:
        return 1
    print(f"OK: {len(versoes)} conjunto(s) de contratos com manifesto SHA-256 conferido.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
