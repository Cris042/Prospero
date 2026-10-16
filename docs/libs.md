# Bibliotecas, ferramentas e serviços

## Inventário adotado

Versões registradas nos POMs, lockfile e imagens Compose. Atualizar este documento quando alterar dependências; não usar `latest`.

| Componente | Versão | Evidência / finalidade |
| --- | --- | --- |
| Java Temurin | 26.0.2.1_1 | Dockerfiles; compilação e runtime |
| Quarkus | 3.40.1 | BOM e plugin dos quatro POMs |
| Maven / Wrapper | 3.9.16 / 3.3.4 | Wrapper independente, distribuição com SHA-256 |
| Hibernate / Flyway | 7.4.9.Final / 12.0.0 | Dependências resolvidas pelo BOM; persistência e migrações |
| SmallRye OpenAPI | 4.3.5 | Swagger e contrato HTTP |
| Kafka cliente / broker | 4.2.1 / 4.1.0 | BOM / Compose KRaft |
| LZ4 Java | 1.11.4 | Override transitivo nos quatro POMs; correção CVE-2026-106451 |
| ArchUnit | 1.5.1 | Fronteiras arquiteturais nos testes Java |
| Angular / Node | 22.2.3 / 24.16.0 | package-lock / imagem builder |
| TypeScript / RxJS | 6.0.3 / 7.8.2 | package-lock |
| Vitest / Playwright | 5.0.5 / 1.64.1 | Testes de componentes / navegador |
| Prettier | 3.9.10 | Formatação frontend |
| Nginx | 1.28.0-alpine | Imagem runtime frontend |
| PostgreSQL / Redis | 16.14-alpine / 8.2.2-alpine | Bancos isolados / projeção futura |
| OpenTelemetry SDK / Collector | 1.62.0 / 0.162.0 | Extensão Quarkus / Collector contrib |
| Micrometer / Prometheus | 1.17.1 / v3.15.0 | Métricas |
| Loki / Grafana / Jaeger | 3.7.8 / 13.2.4 / 2.22.0 | Logs, dashboards e traces |
| ai-memory | 3.1.0 | CLI e container separado; [guia](memoria.md) |
| Gitleaks / Trivy | 8.30.1 / 0.76.0 | CI; imagens fixadas por digest |

## Pendências

Leitor XLSX será escolhido na P03. DDD, SAGA e Object Calisthenics são decisões de projeto, não bibliotecas instaladas. Kafka e Redis preparados na fundação não significam regras financeiras implementadas. As 14 skills e seus hashes estão no [inventário de origens](skills/origens.json).

## Ambiente observado

Docker Engine 29.1.3, Compose 2.40.3. Java do host é 25; builds Java 26 usam container. Node local 24.16.0. CI requer Compose compatível com `!override` e runner dedicado; consulte [CI](ci.md).

## Override de segurança

LZ4 1.11.4 substitui 1.11.1 gerida transitivamente pelo baseline. A auditoria Trivy identificou CVE-2026-106451; o [advisory do mantenedor](https://github.com/yawkat/lz4-java/security/advisories/GHSA-mcr4-qmvw-px4g) indica correção em 1.11.4. Reavaliar e remover o override quando o BOM passar a incorporar uma versão corrigida. O scanner classificou HIGH; o mantenedor classifica LOW e condiciona a exploração ao ambiente de diretório temporário compartilhado.
