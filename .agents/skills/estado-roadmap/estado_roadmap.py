#!/usr/bin/env python3
"""Resume o estado das tarefas de docs/roadmap.md e os PRs abertos, sem ler o roadmap inteiro no contexto."""
import json
import re
import subprocess
import sys
from pathlib import Path

ESTADOS = r"(conclu[ií]da|em andamento|planejada|aguardando revis[aã]o/merge|em valida[cç][aã]o|integrada)"
TAREFA = re.compile(r"^#{2,3} ((?:P|RF|V)\d+[a-z]?\b.*?)\s*$")


def tarefas(roadmap: Path) -> list[tuple[str, str, str]]:
    linhas = roadmap.read_text(encoding="utf-8").splitlines()
    saida, atual, estado, trecho = [], None, "", ""
    for linha in linhas + ["## fim"]:
        cabecalho = re.match(r"^#{2,3} ", linha)
        if cabecalho:
            if atual:
                saida.append((atual, estado or "?", trecho))
            m = TAREFA.match(linha)
            atual, estado, trecho = (m.group(1)[:70] if m else None), "", ""
            continue
        if atual and not estado and "Estado" in linha:
            m = re.search(r"\*\*" + ESTADOS + r"[^*]*\*\*", linha, re.I) or re.search(ESTADOS, linha, re.I)
            if m:
                estado = m.group(1).lower()
                trecho = re.sub(r"\s+", " ", re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", linha)).strip()[:110]
    return saida


def prs_abertos() -> list[dict]:
    try:
        sai = subprocess.run(["gh", "pr", "list", "--state", "open", "--json", "number,title,headRefName,isDraft"],
                             check=True, capture_output=True, text=True).stdout
        return json.loads(sai)
    except (OSError, subprocess.CalledProcessError):
        return []


def main() -> None:
    raiz = Path(subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True).stdout.strip() or ".")
    roadmap = raiz / "docs/roadmap.md"
    if not roadmap.exists():
        sys.exit("docs/roadmap.md não encontrado")
    print("| Tarefa | Estado | Linha de estado |\n| --- | --- | --- |")
    for nome, estado, trecho in tarefas(roadmap):
        print(f"| {nome} | {estado} | {trecho} |")
    prs = prs_abertos()
    print(f"\nPRs abertos: {len(prs)}")
    for pr in prs:
        print(f"- #{pr['number']} {'(draft) ' if pr['isDraft'] else ''}{pr['title']} [{pr['headRefName']}]")


if __name__ == "__main__":
    main()
