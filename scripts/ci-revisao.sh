#!/usr/bin/env bash
set -euo pipefail
python3 scripts/preparar-ambiente.py
python3 scripts/verificar-documentacao.py
python3 scripts/verificar-estrutura.py
python3 -B -m unittest discover -s scripts/tests -p 'test_*.py'
docker compose config --quiet
npm --prefix frontend ci --no-fund --no-audit
npm --prefix frontend run format:check
printf '%s\n' 'Revisão determinística: documentação, skills, fronteiras, Compose e formatação conferidos.'
