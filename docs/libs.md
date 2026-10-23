# Bibliotecas, ferramentas e serviços

## Inventário adotado

Versões registradas nos POMs, lockfile e imagens Compose. Atualizar este documento quando alterar dependências; não usar `latest`.

| Componente | Versão | Evidência / finalidade |
| --- | --- | --- |
| Java Temurin | 26.0.2.1_1 | Dockerfiles; compilação e runtime |
| Quarkus | 3.40.1 | BOM e plugin dos quatro POMs |
| SmallRye JWT (Quarkus) | BOM 3.40.1 | `quarkus-smallrye-jwt` no FO (RF01), no MO (RF04b, audiência `prospero-middle-office`) e no BO (RF07, audiência `prospero-back-office`); `quarkus-smallrye-jwt-build` só em teste |
| Maven / Wrapper | 3.9.16 / 3.3.4 | Wrapper independente, distribuição com SHA-256 |
| Hibernate / Flyway | 7.4.9.Final / 12.0.0 | Dependências resolvidas pelo BOM; persistência e migrações |
| SmallRye OpenAPI | 4.3.5 | Swagger e contrato HTTP |
| Kafka cliente / broker | 4.2.1 / 4.1.0 | BOM / Compose KRaft |
| LZ4 Java | 1.11.4 | Override transitivo nos quatro POMs; correção CVE-2026-106451 |
| ArchUnit | 1.5.1 | Fronteiras arquiteturais nos testes Java |
| Angular / Node | 22.2.3 / 24.16.0 | package-lock / imagem builder |
| TypeScript / RxJS | 6.0.3 / 7.8.2 | package-lock |
| @types/node | 24.0.0 | Somente desenvolvimento: tipos Node24 para E2E com arquivo privado/crypto; `undici-types` transitivo, package-lock |
| Vitest / Playwright | 5.0.5 / 1.64.1 | Testes de componentes / navegador |
| ExcelJS / yauzl | 4.4.0 / 3.4.0 | Runner XLSX Node (fora do bundle); preflight ZIP limitado, package-lock |
| UUID (override ExcelJS) | 11.1.1 | Correção GHSA-w5hq-g745-h8pq; API CommonJS v4 usada pelo ExcelJS |
| Prettier | 3.9.10 | Formatação frontend |
| Nginx | 1.28.0-alpine | Imagem runtime frontend |
| PostgreSQL / Redis | 16.14-alpine / 8.2.2-alpine | Bancos isolados / projeção futura |
| OpenTelemetry SDK / Collector | 1.62.0 / 0.162.0 | Extensão Quarkus / Collector contrib |
| Micrometer / Prometheus | 1.17.1 / v3.15.0 | Métricas |
| Loki / Grafana / Jaeger | 3.7.8 / 13.2.4 / 2.22.0 | Logs, dashboards e traces |
| ai-memory | 3.1.0 | CLI e container separado; [guia](rules.md#memória-e-retomada) |
| actions/upload-artifact | 7.0.2 | Capturas PNG Playwright por sete dias na CI; commit `cf430e030ddbb5b0abf93d22962f4752f3646cd9`, [origem oficial](https://github.com/actions/upload-artifact/tree/cf430e030ddbb5b0abf93d22962f4752f3646cd9) |
| Gitleaks / Trivy | 8.30.1 / 0.76.0 | CI; imagens fixadas por digest |
| Go | 1.27.3 | Módulo `ferramentas/carga`; API e executor técnicos com biblioteca padrão, sem módulos runtime externos |
| Imagem builder Go | 1.27.3-bookworm | Dockerfile e CI; digest `sha256:21ade014faf952365b9ecbd8dbdb87176fbacb82fac9fb132d9f2a7b8cc3f132`; runtime `scratch`, binário estático sem shell |
| govulncheck | 1.8.0 | Scanner oficial de desenvolvimento/CI, instalado por versão e verificado pelo Go checksum database; fora do módulo runtime |

## Pendências

Runner XLSX implementado na P03, com aceite/PR no roadmap. DDD, SAGA e Object Calisthenics são decisões de projeto, não bibliotecas instaladas. Kafka e Redis preparados na fundação não significam regras financeiras implementadas. As 16 skills e seus hashes estão no [inventário de origens](skills/origens.json).

## Go e ferramentas de carga — adoção P06

Go 1.27.3 e `net/http` com executor nativo restrito foram adotados conforme [ADR-006](adrs/ADR-006-api-carga.md). O módulo independente usa somente biblioteca padrão em runtime; não instala Huma, Vegeta ou cliente Prometheus. A exposição textual de métricas tem nomes e labels finitos, com séries de recursos obtidas do processo; API e gerador compartilham essas medições. [HLD-006](hlds/HLD-006-api-carga.md) registra limites, contrato, fontes e pendências da etapa A. Não equivale a capacidade financeira ou RNF04 comprovado.

Experimento descartável com Go 1.27.2, Huma 2.39.1/humago e Vegeta 12.12.0: contratos válidos/negativos, sanitização e OpenAPI; streaming contínuo demonstrou que `MaxBody` limita retenção, mas mantém drenagem, e `Stop` não abortou imediatamente a leitura. Executor nativo usa leitura limitada e contexto cancelável; a decisão e os limites de interpretação ficam no ADR. Essas dependências pertencem somente ao experimento fora do repositório.

Builder oficial fixado por digest consultado no registry Docker Hub; CI usa a mesma imagem com GCC para `go test -race`, `gofmt` e `go vet`. A imagem final `scratch` contém somente o binário Go estático, usuário 65532 e healthcheck implementado pelo próprio binário. Não há dependência Debian no runtime. O scanner [govulncheck 1.8.0](https://github.com/golang/vuln/releases/tag/v1.8.0) (1.1.4 entrava em pânico no SSA do stdlib Go 1.27) é instalado temporariamente com versão fixada e consulta a [base oficial](https://go.dev/doc/security/vuln/); falha de rede/instalação/scan reprova o check. Sem `latest` e sem interpretar uma etapa não executada como aprovação.

## Ambiente observado

Docker Engine 29.1.3, Compose 2.40.3. Java do host é 25; builds Java 26 usam container. Node local 24.16.0. CI usa runner hospedado `ubuntu-24.04` gratuito com Compose compatível com `!override`; consulte [CI](rules.md#ci-e-runner).

## Override de segurança

Go 1.27.3 substitui 1.27.2 no módulo/builder/CI para corrigir [GO-2026-6809](https://pkg.go.dev/vuln/GO-2026-6809), detectada pelo govulncheck em 2026-10-21. O digest do índice OCI foi conferido no registry oficial `library/golang`; o histórico do experimento em 1.27.2 permanece inalterado. A atualização mantém código e contratos da ferramenta de carga; resultados do scanner e regressões ficam no PR #27.

O robô formador (RF05d.2, `ferramentas/robo`) reutiliza o mesmo builder Go 1.27.3 fixado por digest, imagem final `scratch` e verificações da CI (`gofmt`, `go vet`, `go test -race`, govulncheck 1.8.0); somente biblioteca padrão, incluindo `time/tzdata` embutido para a sessão em America/Sao_Paulo. Nenhuma dependência nova.

LZ4 1.11.4 substitui 1.11.1 gerida transitivamente pelo baseline. A auditoria Trivy identificou CVE-2026-106451; o [advisory do mantenedor](https://github.com/yawkat/lz4-java/security/advisories/GHSA-mcr4-qmvw-px4g) indica correção em 1.11.4. Reavaliar e remover o override quando o BOM passar a incorporar uma versão corrigida. O scanner classificou HIGH; o mantenedor classifica LOW e condiciona a exploração ao ambiente de diretório temporário compartilhado.

## Leitura XLSX

ExcelJS 4.4.0 (MIT) lê/escreve o exemplo e os fixtures, conforme [documentação oficial fixada](https://github.com/exceljs/exceljs/blob/v4.4.0/README.md). A alternativa [read-excel-file](https://github.com/catamphetamine/read-excel-file) separaria leitura e geração; ExcelJS permite as duas capacidades com o mesmo modelo, ao custo de mais transitivas. O parser não executa fórmulas nem resolve links externos. [yauzl](https://github.com/thejoshwolfe/yauzl) valida entradas/tamanhos e permite contabilizar bytes realmente descomprimidos antes de ExcelJS. Limites e contrato ficam no [README frontend](../frontend/README.md#runner-xlsx--contrato-p03).

O override UUID 11.1.1 mantém exportação CommonJS `v4`, único uso no ExcelJS (`cf-rule-ext-xform`); corrige [GHSA-w5hq-g745-h8pq](https://github.com/advisories/GHSA-w5hq-g745-h8pq). Remover quando ExcelJS atualizar sua dependência. Auditoria produção executada localmente em 2026-10-17 após instalação: zero vulnerabilidades; não substitui auditorias futuras nem scan de imagens.
