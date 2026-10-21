#!/usr/bin/env bash
set -euo pipefail
python3 scripts/preparar-ambiente.py
python3 scripts/verificar-documentacao.py
python3 scripts/verificar-estrutura.py
python3 -B -m unittest discover -s scripts/tests -p 'test_*.py'
docker compose config --quiet
docker compose -f docker-compose.yml -f docker-compose.carga.yml --profile carga config --quiet
npm --prefix frontend ci --no-fund --no-audit
npm --prefix frontend run format:check
# Verifica Go sem modificar os fontes e sem resolver módulos externos.
docker run --rm --user "$(id -u):$(id -g)" --memory=1g --cpus=2 --pids-limit=128 \
  --network=none --read-only --tmpfs /tmp:rw,size=536870912 \
  -e GOCACHE=/tmp/go-build -e GOTOOLCHAIN=local \
  -v "$PWD/ferramentas/carga:/src:ro" -w /src \
  golang:1.27.3-bookworm@sha256:21ade014faf952365b9ecbd8dbdb87176fbacb82fac9fb132d9f2a7b8cc3f132 sh -ec 'test -z "$(gofmt -l .)"; go vet ./...'
printf '%s\n' 'Revisão determinística: documentação, skills, fronteiras, Compose e formatação conferidos.'
