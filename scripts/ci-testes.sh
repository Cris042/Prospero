#!/usr/bin/env bash
set -euo pipefail
for value in "${GITHUB_RUN_ID:-}" "${GITHUB_RUN_ATTEMPT:-}"; do
  case "$value" in ''|*[!0-9]*) echo 'Identidade numérica de execução ausente.' >&2; exit 2;; esac
done
python3 scripts/ci-preflight.py
# Registra disco e uso Docker para diagnosticar falta de espaço no runner efêmero.
df -h / "${RUNNER_TEMP:-/tmp}" || true
docker system df || true
export PROSPERO_CI_TAG="ci-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
project="prospero-ci-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
compose=(docker compose -p "$project" -f docker-compose.yml -f docker-compose.ci.yml)
mkdir -p .ci-evidencias
cleanup() {
  code=$?
  trap - EXIT INT TERM
  if [ "$code" -ne 0 ]; then
    "${compose[@]}" logs --no-color --tail=80 > .ci-evidencias/compose.log 2>&1 || true
  fi
  "${compose[@]}" down --volumes --remove-orphans || true
  rm -rf -- "${runner_cfg:-}"
  for service in front-office middle-office matching-engine back-office frontend runner-planilhas; do
    docker image rm "prospero/$service:$PROSPERO_CI_TAG" >/dev/null 2>&1 || true
  done
  exit "$code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
python3 scripts/preparar-ambiente.py
"${compose[@]}" config --quiet
cache="${RUNNER_TEMP:?Runner temporário não definido}/prospero-maven"
mkdir -p "$cache"
for service in front-office middle-office matching-engine back-office; do
  docker run --rm --user "$(id -u):$(id -g)" --memory=1g --cpus=2 \
    -e MAVEN_USER_HOME=/maven -e MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2 -Duser.home=/tmp/prospero -Dmaven.repo.local=/maven/repository' \
    -v "$PWD:/workspace" -v "$cache:/maven" -w "/workspace/$service" \
    eclipse-temurin:26.0.2.1_1-jdk-noble sh ./mvnw -B -ntp verify
 done
# Race usa o toolchain com GCC; os alvos dos testes são servidores descartáveis.
docker run --rm --user "$(id -u):$(id -g)" --memory=1g --cpus=2 --pids-limit=128 \
  --network=none --read-only --tmpfs /tmp:rw,exec,size=536870912 \
  -e GOCACHE=/tmp/go-build -e GOTMPDIR=/tmp -e GOTOOLCHAIN=local -e CGO_ENABLED=1 \
  -v "$PWD/ferramentas/carga:/src:ro" -w /src \
  golang:1.27.2-bookworm@sha256:5cf287a799e6b94384bad13d16b14904c531f51ba65792237e122ce42b392f61 go test -race -count=1 ./...
npm --prefix frontend ci --no-fund --no-audit
npm --prefix frontend run test:runner
npm --prefix frontend test -- --watch=false
npm --prefix frontend run build
COMPOSE_PARALLEL_LIMIT=1 "${compose[@]}" --progress plain build
"${compose[@]}" build runner-planilhas
"${compose[@]}" up -d --wait --wait-timeout 240
python3 scripts/verificar-ambiente.py --projeto "$project"
"${compose[@]}" run --rm --no-deps -T runner-planilhas
# RF01/RF02: workbooks com tokens curtos (preparação e contas de teste) em arquivo temporário fora do Git e da imagem.
runner_cfg="$(mktemp -d "${RUNNER_TEMP:?}/prospero-runner.XXXXXX")"
# workbook:perfil — cada planilha recebe só os tokens do seu perfil.
for item in mercado:mercado ordens:ordens validacao:ordens; do
  workbook="${item%%:*}" perfil="${item##*:}"
  python3 scripts/mercado.py config-runner "$runner_cfg/$workbook.json" --perfil "$perfil"
  "${compose[@]}" run --rm --no-deps -T -v "$runner_cfg/$workbook.json:/config/runner.json:ro" \
    runner-planilhas "runner/exemplos/$workbook.xlsx" --config /config/runner.json
done
fo_origem="http://$("${compose[@]}" port front-office 8080)"
python3 scripts/mercado.py verificar-ordens --origem "$fo_origem"
# RF04: SAGA FO→MO com carteiras semeadas; volumes novos a cada execução da CI.
python3 scripts/mercado.py verificar-risco --origem "$fo_origem"
python3 scripts/mercado.py preparar-demo --origem "$fo_origem"
export BASE_URL="http://$("${compose[@]}" port frontend 8080)"
# Runner hospedado é efêmero: instala as bibliotecas do Chromium; host local não é alterado.
playwright_deps=()
[ "${RUNNER_ENVIRONMENT:-}" = github-hosted ] && playwright_deps=(--with-deps)
(cd frontend && npm exec playwright install "${playwright_deps[@]}" chromium && npm run test:e2e)
printf '%s\n' 'Testes independentes, aplicação empacotada e observabilidade validados.'
