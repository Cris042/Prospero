#!/usr/bin/env bash
set -euo pipefail
python3 scripts/verificar-documentacao.py
python3 scripts/verificar-estrutura.py
npm --prefix frontend audit --omit=dev --audit-level=high
[ "$(git rev-parse --is-shallow-repository)" = false ] || { echo "Histórico incompleto para scan" >&2; exit 1; }
# Todo scan redige possíveis segredos. Arquivos locais de credenciais não são publicação.
if git ls-files --error-unmatch .env >/dev/null 2>&1; then echo "ERRO: .env versionado" >&2; exit 1; fi
docker run --rm --user "$(id -u):$(id -g)" -e GIT_CONFIG_COUNT=1 -e GIT_CONFIG_KEY_0=safe.directory -e GIT_CONFIG_VALUE_0=/repo -v "$PWD:/repo:ro" \
  zricethezav/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f \
  git /repo --config /repo/.gitleaks.toml --redact --no-banner
# Resolver os JARs reais: o BOM não é tratado como uma lista fictícia de versões.
cache="${RUNNER_TEMP:?Runner temporário não definido}/prospero-maven"
mkdir -p "$cache"
for service in front-office middle-office matching-engine back-office; do
  docker run --rm --user "$(id -u):$(id -g)" --memory=1g --cpus=2 \
    -e MAVEN_USER_HOME=/maven -e MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2 -Duser.home=/tmp/prospero -Dmaven.repo.local=/maven/repository' \
    -v "$PWD:/workspace" -v "$cache:/maven" -w "/workspace/$service" \
    eclipse-temurin:26.0.2.1_1-jdk-noble sh ./mvnw -B -ntp package -DskipTests
 done
# Analisar os artefatos empacotados; não resolver novamente os POMs via Maven Central.
scan_dir="$(mktemp -d "${RUNNER_TEMP:?}/prospero-scan.XXXXXX")"
trap 'rm -rf -- "$scan_dir"' EXIT
mkdir -p "$scan_dir/frontend" "$cache/trivy"
cp frontend/package.json frontend/package-lock.json "$scan_dir/frontend/"
for service in front-office middle-office matching-engine back-office; do
  mkdir -p "$scan_dir/$service"
  cp -R "$service/target/quarkus-app/lib" "$scan_dir/$service/"
done
trivy_image='aquasec/trivy:0.76.0@sha256:2416d1f74045b57274df1a340eee6b510cacd73c66a6ccb8da092b117eb7d222'
scanner=(docker run --rm --user "$(id -u):$(id -g)" --memory=1g
  -v "$scan_dir:/scan:ro" -v "$cache/trivy:/cache" "$trivy_image")
# Rootfs reconhece JARs; fs reconhece lockfiles. Não tratar saída vazia como aprovação Java.
for service in front-office middle-office matching-engine back-office; do
  scan_rc=0
  "${scanner[@]}" rootfs --timeout 20m --cache-dir /cache --offline-scan --scanners vuln \
    --severity HIGH,CRITICAL --exit-code 1 --list-all-pkgs --format json "/scan/$service" > "$scan_dir/$service.json" || scan_rc=$?
  python3 scripts/verificar-scan-java.py "$scan_dir/$service.json"
  [ "$scan_rc" -eq 0 ] || exit "$scan_rc"
done
"${scanner[@]}" fs --cache-dir /cache --offline-scan --scanners vuln \
  --severity HIGH,CRITICAL --exit-code 1 /scan/frontend
printf '%s\n' 'Segurança: inventário, fronteiras, dependências e segredos verificados.'
