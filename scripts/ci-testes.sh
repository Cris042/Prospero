#!/usr/bin/env bash
set -euo pipefail
for value in "${GITHUB_RUN_ID:-}" "${GITHUB_RUN_ATTEMPT:-}"; do
  case "$value" in ''|*[!0-9]*) echo 'Identidade numérica de execução ausente.' >&2; exit 2;; esac
done
python3 scripts/ci-preflight.py
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
npm --prefix frontend ci --no-fund --no-audit
npm --prefix frontend run test:runner
npm --prefix frontend test -- --watch=false
npm --prefix frontend run build
COMPOSE_PARALLEL_LIMIT=1 "${compose[@]}" --progress plain build
"${compose[@]}" build runner-planilhas
"${compose[@]}" up -d --wait --wait-timeout 240
python3 scripts/verificar-ambiente.py --projeto "$project"
"${compose[@]}" run --rm --no-deps -T runner-planilhas
export BASE_URL="http://$("${compose[@]}" port frontend 8080)"
(cd frontend && npm exec playwright install chromium && npm run test:e2e)
printf '%s\n' 'Testes independentes, aplicação empacotada e observabilidade validados.'
