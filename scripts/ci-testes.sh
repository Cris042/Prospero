#!/usr/bin/env bash
set -euo pipefail
for value in "${GITHUB_RUN_ID:-}" "${GITHUB_RUN_ATTEMPT:-}"; do
  case "$value" in ''|*[!0-9]*) echo 'Identidade numérica de execução ausente.' >&2; exit 2;; esac
done
python3 scripts/ci-preflight.py
# V01a: comportamento das regras com relógio/séries sintéticos, sem subir telemetria adicional.
promtool=(docker run --rm --network=none --read-only --tmpfs /tmp:rw,size=67108864
  --memory=256m --cpus=1 --pids-limit=64 --entrypoint /bin/promtool
  -v "$PWD/infra/prometheus:/etc/prometheus:ro" -w /etc/prometheus prom/prometheus:v3.15.0)
"${promtool[@]}" check config /etc/prometheus/prometheus.yml
"${promtool[@]}" check rules /etc/prometheus/alertas.yml
"${promtool[@]}" test rules /etc/prometheus/alertas.test.yml
# Registra disco e uso Docker para diagnosticar falta de espaço no runner efêmero.
df -h / "${RUNNER_TEMP:-/tmp}" || true
docker system df || true
export PROSPERO_CI_TAG="ci-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
project="prospero-ci-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
# RF05d: o arquivo do robô entra desde o início (FO já na rede dele); o perfil opt-in só liga no fim, com tag da execução.
compose=(docker compose -p "$project" -f docker-compose.yml -f docker-compose.ci.yml -f docker-compose.robo.yml -f docker-compose.carga.yml -f docker-compose.carga-ci.yml)
export PROSPERO_ROBO_TAG="$PROSPERO_CI_TAG"
compose_robo=("${compose[@]}" --profile robo --profile carga)
compose_carga=("${compose[@]}" --profile carga)
mkdir -p .ci-evidencias
cleanup() {
  code=$?
  trap - EXIT INT TERM
  if [ "$code" -ne 0 ]; then
    "${compose[@]}" logs --no-color --tail=80 > .ci-evidencias/compose.log 2>&1 || true
    grafana_cid="$("${compose[@]}" ps -a -q grafana 2>/dev/null || true)"
    if [[ "$grafana_cid" =~ ^[0-9a-f]{12,64}$ ]]; then
      docker inspect --format 'Grafana: estado={{.State.Status}} OOM={{.State.OOMKilled}} saida={{.State.ExitCode}}' "$grafana_cid" || true
    fi
  fi
  "${compose_robo[@]}" down --volumes --remove-orphans || true
  rm -rf -- "${runner_cfg:-}" "${carga_cfg:-}"
  for service in front-office middle-office matching-engine back-office frontend runner-planilhas robo-formador api-carga; do
    docker image rm "prospero/$service:$PROSPERO_CI_TAG" >/dev/null 2>&1 || true
  done
  exit "$code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# Compatibilidade das provas D0 históricas; a fase RF08e ao fim exige a guarda ativa.
export PROSPERO_CI_GUARDA_LIQUIDACAO=false
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
for ferramenta in carga robo; do
  docker run --rm --user "$(id -u):$(id -g)" --memory=1g --cpus=2 --pids-limit=128 \
    --network=none --read-only --tmpfs /tmp:rw,exec,size=536870912 \
    -e GOCACHE=/tmp/go-build -e GOTMPDIR=/tmp -e GOTOOLCHAIN=local -e CGO_ENABLED=1 \
    -v "$PWD/ferramentas/$ferramenta:/src:ro" -w /src \
    golang:1.27.3-bookworm@sha256:21ade014faf952365b9ecbd8dbdb87176fbacb82fac9fb132d9f2a7b8cc3f132 go test -race -count=1 ./...
done
npm --prefix frontend ci --no-fund --no-audit
npm --prefix frontend run test:proxy
npm --prefix frontend run test:runner
npm --prefix frontend test -- --watch=false
npm --prefix frontend run build
COMPOSE_PARALLEL_LIMIT=1 "${compose[@]}" --progress plain build
"${compose[@]}" build runner-planilhas
"${compose[@]}" up -d --wait --wait-timeout 240
python3 scripts/verificar-ambiente.py --projeto "$project"
python3 scripts/testar-matching-postgres.py --projeto "$project" --cache "$cache"
"${compose[@]}" run --rm --no-deps -T runner-planilhas
# RF01/RF02: workbooks com tokens curtos (preparação e contas de teste) em arquivo temporário fora do Git e da imagem.
runner_cfg="$(mktemp -d "${RUNNER_TEMP:?}/prospero-runner.XXXXXX")"
# Cenários que preparam carteiras antecedem Stop/OCO vivos no livro, que bloqueiam nova preparação.
rodar_workbook() {
  local workbook="$1" perfil="$2"
  python3 scripts/mercado.py config-runner "$runner_cfg/$workbook.json" --perfil "$perfil"
  "${compose[@]}" run --rm --no-deps -T -v "$runner_cfg/$workbook.json:/config/runner.json:ro" \
    runner-planilhas "runner/exemplos/$workbook.xlsx" --config /config/runner.json
}
rodar_workbook mercado mercado
# RF07: DELTA3 antes das outras negociações; repete fechando posições e PnL por APIs.
rodar_workbook posicoes posicoes
rodar_workbook posicoes posicoes
# RF08b: negócios e razão D0 em DELTA3 logo após posições; repete abrindo e fechando por negócios reais.
rodar_workbook razao razao
rodar_workbook razao razao
# B1: somente 12 leituras logicas, preflight real e controlador do proprio projeto.
carga_cfg="$(mktemp -d "${RUNNER_TEMP:?}/prospero-carga.XXXXXX")"
export PROSPERO_CARGA_SECRETS_DIR="$carga_cfg"
python3 scripts/testar-carga-integrada.py --projeto "$project" --privados "$carga_cfg" --preparar
"${compose_carga[@]}" build api-carga
"${compose_carga[@]}" up -d --wait --wait-timeout 90 api-carga
timeout --kill-after=15s 90s python3 scripts/testar-carga-integrada.py --projeto "$project" --privados "$carga_cfg" \
  > .ci-evidencias/carga-leituras.json
cat .ci-evidencias/carga-leituras.json
"${compose_carga[@]}" stop --timeout 8 api-carga
"${compose_carga[@]}" rm -f api-carga
rm -rf -- "$carga_cfg"
carga_cfg=''
# RF09a: recebimento, risco rejeitado, cursor e isolamento; duas execuções sem reconstruir fatos.
rodar_workbook trajetoria risco
rodar_workbook trajetoria risco
# RF09b: recuperação com os mesmos bancos/logs, spans de processamento e queda limitada do coletor.
fo_origem="http://$("${compose[@]}" port front-office 8080)"
mo_origem="http://$("${compose[@]}" port middle-office 8080)"
bo_origem="http://$("${compose[@]}" port back-office 8080)"
jaeger_origem="http://$("${compose[@]}" port jaeger 16686)"
timeout 600 python3 scripts/testar-trajetoria.py --projeto "$project" --origem "$fo_origem" \
  --origem-mo "$mo_origem" --origem-bo "$bo_origem" --origem-jaeger "$jaeger_origem" \
  > .ci-evidencias/trajetoria.json
cat .ci-evidencias/trajetoria.json
# Reinício/recriação pode renovar portas; o harness restaura worker=false antes de retornar.
fo_origem="http://$("${compose[@]}" port front-office 8080)"
bo_origem="http://$("${compose[@]}" port back-office 8080)"
# RF06a: livro real, VWAP e repetição sem zerar acumuladores ou reservas.
rodar_workbook cotacao risco
rodar_workbook cotacao risco
# Repetição deliberada comprova limpeza por negócios reais, sem apagar reservas/livro.
rodar_workbook matching risco
rodar_workbook matching risco
fo_origem="http://$("${compose[@]}" port front-office 8080)"
mo_origem="http://$("${compose[@]}" port middle-office 8080)"
# RF05d: primeira medição RNF04, informativa (runner hospedado, ruidoso): nunca é gate de latência.
me_origem="http://$("${compose[@]}" port matching-engine 8080)"
mkdir -p .ci-evidencias
# Falha funcional (ordem que não executa) falha o gate; latência acima da meta, nunca.
timeout 600 python3 scripts/medir-rnf04.py --origem "$fo_origem" --origem-mo "$mo_origem" --origem-me "$me_origem" \
  --contexto ci-hospedado --amostras 150 --aquecimento 30 > .ci-evidencias/rnf04.json
cat .ci-evidencias/rnf04.json
python3 scripts/mercado.py verificar-matching --origem "$fo_origem" --origem-mo "$mo_origem" --projeto "$project"
python3 scripts/mercado.py verificar-concorrencia --origem-mo "$mo_origem" --projeto "$project"
python3 scripts/mercado.py verificar-recuperacao --origem "$fo_origem" --origem-mo "$mo_origem" --projeto "$project"
# start/restart pode alterar a porta dinâmica publicada na CI.
mo_origem="http://$("${compose[@]}" port middle-office 8080)"
# RF04 deixa o OCO final vivo na CONTA-003; a prova seguinte usa CONTA-002, sem apagar esse fato.
rodar_workbook risco risco
python3 scripts/mercado.py verificar-risco --origem "$fo_origem" --origem-mo "$mo_origem"
# RF02/RF03 ainda registram Stop/OCO; nenhuma preparação de carteira após essas provas.
rodar_workbook ordens ordens
rodar_workbook validacao ordens
python3 scripts/mercado.py verificar-ordens --origem "$fo_origem"
python3 scripts/mercado.py preparar-demo --origem "$fo_origem"
bo_origem="http://$("${compose[@]}" port back-office 8080)"
# Runner hospedado é efêmero: instala as bibliotecas do Chromium; host local não é alterado.
# Instalado antes de recriar o BO: o apt do --with-deps disputava CPU com a projeção recém-iniciada
# e deixava a prontidão vencer (emDia expira após 10 s sem reconferência), gerando 503/lentidão no 1º E2E.
playwright_deps=()
[ "${RUNNER_ENVIRONMENT:-}" = github-hosted ] && playwright_deps=(--with-deps)
(cd frontend && npm exec playwright install "${playwright_deps[@]}" chromium)
# RF09b: relay ativo durante os E2E confirma contabilização; restaura false antes da prova RF08e.
PROSPERO_CI_WORKER_LIQUIDACAO=true "${compose[@]}" up -d --no-deps --force-recreate --wait --wait-timeout 120 back-office
bo_origem="http://$("${compose[@]}" port back-office 8080)"
# Credenciais ficam no arquivo privado temporário, acessível só ao processo Node do teste.
python3 scripts/mercado.py config-runner "$runner_cfg/posicoes-e2e.json" --perfil posicoes \
  --origem "$fo_origem" --origem-mo "$mo_origem" --origem-bo "$bo_origem"
chmod 600 "$runner_cfg/posicoes-e2e.json"
export PROSPERO_POSICOES_E2E_CONFIG="$runner_cfg/posicoes-e2e.json"
# Consultas/regras/provisionamento reais; credencial Grafana somente em arquivo privado temporário.
python3 scripts/verificar-observabilidade.py --projeto "$project" --config-e2e "$runner_cfg/grafana.json"
export PROSPERO_GRAFANA_E2E_CONFIG="$runner_cfg/grafana.json"
export BASE_URL="http://$("${compose[@]}" port frontend 8080)"
# Os E2E só começam com a projeção do BO em dia, comprovada por 3 respostas 200 seguidas pelo proxy real.
aguardar_posicoes() {
  local seguidas=0
  for _ in $(seq 1 60); do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$BASE_URL/servicos/back-office/api/v1/posicoes")" = 200 ]; then
      seguidas=$((seguidas + 1)); [ "$seguidas" -ge 3 ] && return 0
    else
      seguidas=0
    fi
    sleep 1
  done
  echo 'Projeção de posições do BO não ficou em dia antes dos E2E.' >&2
  return 1
}
diagnosticar_e2e() {
  echo '== Diagnóstico pós-falha E2E (BO, MO, FO; últimos 4 min)'
  "${compose[@]}" logs --no-color --since 4m back-office middle-office front-office 2>&1 | tail -n 300 || true
  docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' 2>&1 | grep -F "$project" || true
}
aguardar_posicoes
(cd frontend && npm run test:e2e) || { diagnosticar_e2e; exit 1; }
PROSPERO_CI_WORKER_LIQUIDACAO=false "${compose[@]}" up -d --no-deps --force-recreate --wait --wait-timeout 120 back-office
bo_origem="http://$("${compose[@]}" port back-office 8080)"
# RF05d.2: robô em DELTA3, que nenhuma prova seguinte usa; para ao fim, com as ofertas DIA vivas (o encerramento é só GAMA3).
"${compose_robo[@]}" build robo-formador
python3 scripts/mercado.py verificar-robo --origem "$fo_origem" --projeto "$project"
# RF05c.4: encerramento por último; o ativo passa a recusar ordens na data (uma sessão por log), não repetível.
rodar_workbook sessao sessao
# RF08e: fim da negociação, nenhuma preparação entre referência e conciliação.
# A fase legada acima não serve como evidência da proteção de pendências.
export PROSPERO_CI_GUARDA_LIQUIDACAO=true
"${compose[@]}" up -d --no-deps --force-recreate --wait --wait-timeout 120 middle-office
mo_origem="http://$("${compose[@]}" port middle-office 8080)"
bo_origem="http://$("${compose[@]}" port back-office 8080)"
timeout 600 python3 scripts/liquidacao.py --origem-bo "$bo_origem" --origem-mo "$mo_origem" \
  --projeto "$project" --falhas > .ci-evidencias/liquidacao.json
python3 -c 'import json; r=json.load(open(".ci-evidencias/liquidacao.json")); assert r["roboCreditado"] and r["ladosConciliados"]>50 and not r["diferencas"]'
# kill/start pode renovar portas publicadas; tokens e nomes internos do runner permanecem próprios.
mo_origem="http://$("${compose[@]}" port middle-office 8080)"
bo_origem="http://$("${compose[@]}" port back-office 8080)"
rodar_workbook liquidacao liquidacao
rodar_workbook liquidacao liquidacao
# Segunda conciliação, já D+2: nenhum crédito pendente, nenhum efeito adicional.
timeout 240 python3 scripts/liquidacao.py --origem-bo "$bo_origem" --origem-mo "$mo_origem" > .ci-evidencias/liquidacao-repetida.json
python3 -c 'import json; r=json.load(open(".ci-evidencias/liquidacao-repetida.json")); assert r["ladosConciliados"]==0 and not r["diferencas"]'
# Captura do estado D+2 real, preservando também as capturas da fase D0.
(cd frontend && PROSPERO_HISTORICO_LIQUIDADO=true npm run test:e2e -- --grep 'histórico consulta APIs reais' --output test-results-liquidado)
printf '%s\n' 'Testes independentes, aplicação empacotada e observabilidade validados.'
