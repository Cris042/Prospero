# Testes independentes da P02

Verificação em 2026-10-16, com escopo de fundação técnica. A indisponibilidade controlada nos testes de navegador intercepta apenas a identidade HTTP do middle office; os demais serviços são consultados de verdade. Isso verifica isolamento da interface, sem demonstrar recuperação de Kafka, banco ou SAGA.

## Resultados observados

- `cd frontend && npm test -- --watch=false`: cinco testes Angular aprovados em dois arquivos; Vitest 5.0.5, duração informada de 7,84 s.
- `cd frontend && npx playwright test --list`: quatro execuções descobertas (dois cenários em desktop e mobile). Descoberta não equivale a aprovação.
- `cd frontend && npm audit --omit=dev --audit-level=high`: zero vulnerabilidades, saída 0. Escopo de dependências de produção.
- `cd frontend && LD_LIBRARY_PATH=/home/mclovin/.local/pw-libs/usr/lib/x86_64-linux-gnu npx playwright test`: quatro execuções aprovadas em 4,4 s, contra Compose real. A primeira tentativa falhou antes de abrir páginas por ausência de `libasound.so.2` no caminho padrão; a biblioteca já instalada foi encontrada no diretório informado, sem instalação adicional. Capturas desktop e mobile geradas e inspecionadas.
- `python3 scripts/verificar-ambiente.py`: primeira execução aprovou Angular, os seis checks por serviço, Prometheus (quatro alvos), Grafana, Loki e Collector; falhou no endpoint legado `/api/services` do Jaeger 2.22.0 (HTTP 404). Consulta independente a `/api/v3/services` retornou os quatro microsserviços, e Loki confirmou logs dos quatro. O verificador foi corrigido para `/api/v3/services` e repetido: todos os 31 checks aprovaram, saída 0, incluindo traces Jaeger e logs Loki dos quatro microsserviços. A primeira falha foi do contrato do verificador, sem ausência de telemetria.
- `docker run --rm --memory=1g --cpus=2 -e MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2' -v "$PWD:/workspace" -v /home/mclovin/.m2:/root/.m2 -w /workspace eclipse-temurin:26.0.2.1_1-jdk-noble sh ./mvnw -B -ntp test`: BUILD SUCCESS, 36 testes (16 de contrato HTTP e 20 regras ArchUnit), zero falhas, erros ou ignorados. Quatro módulos aprovados; duração total informada 1min15s. Relatórios conferidos em `*/target/surefire-reports/TEST-*.xml`.

## Cobertura dos testes de navegador

O cenário normal exige respostas HTTP 200 de identidade e prontidão dos quatro serviços, cartões disponíveis, nova consulta de oito endpoints pelo botão Atualizar, ausência de erros JavaScript/console e ausência de transbordamento horizontal. Gera capturas completas `P02-desktop.png` e `P02-mobile.png` somente após as asserções aprovarem.

O cenário de falha injeta HTTP 503 na identidade de um único serviço, exige que os outros três continuem disponíveis e restaura a consulta real para verificar a recuperação pela atualização manual. Não usa sleeps fixos nem respostas simuladas de sucesso.

A P02 não introduz comportamento financeiro: dispensa de nova planilha pela linha “Documentação, formatação ou atualização técnica sem mudança funcional” do ADR-004. O runner permanece escopo da P03.

A configuração respeita `BASE_URL` (padrão `http://127.0.0.1:4200`) e o caminho opcional `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH`. Usa um worker para reduzir consumo local e retém trace e captura quando há falha.

## Reteste Java após correção transitiva LZ4

Após o override explícito de `at.yawk.lz4:lz4-java` para 1.11.4 nos quatro POMs, foi executado `./mvnw -B -ntp verify` com Java 26 em container limitado a 1 GiB, duas CPUs e `MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2'`. Front office, middle office e matching engine concluíram testes e empacotamento. No quarto módulo, o processo acumulou pressão de memória: container próximo de 1 GiB, swap do host cheia e avisos de event loop bloqueado na inicialização de rede do back office. Sem erro HTTP 429 observado.

Com autorização do implementador, foi encerrado somente esse container de verificação e o back office foi executado isoladamente, em JVM nova e com os mesmos limites. Resultado: `BUILD SUCCESS` em 30,905 s; nove testes aprovados. XMLs finais conferidos: 36 testes nos quatro serviços, zero falhas, erros ou ignorados. Não se declara sucesso integral do primeiro processo reator, que foi interrompido.

Nos quatro diretórios `target/quarkus-app/lib`, foi confirmada uma única biblioteca LZ4, `at.yawk.lz4.lz4-java-1.11.4.jar`, sem 1.11.1. O arquivo `META-INF/maven/at.yawk.lz4/lz4-java/pom.properties` dentro de cada JAR também declara `version=1.11.4`. A confirmação das imagens reconstruídas e do ambiente Compose é posterior a este reteste.

## Confirmação final do runtime reconstruído

Após a reconstrução serial das quatro imagens Java e recriação do Compose pelo implementador, `python3 scripts/verificar-ambiente.py` aprovou novamente os 31 checks, com saída 0: aplicações, PostgreSQL/Kafka, contratos, proxy, métricas e observabilidade real dos quatro serviços.

A inspeção independente com `docker compose exec -T <serviço> sh -c 'ls /app/lib/main/*lz4*'` confirmou nos quatro containers uma única correspondência: `/app/lib/main/at.yawk.lz4.lz4-java-1.11.4.jar`. Não há LZ4 1.11.1 nesses caminhos do runtime. Angular e Playwright não foram repetidos porque a interface permaneceu inalterada.
