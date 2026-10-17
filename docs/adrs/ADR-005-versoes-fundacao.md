# ADR-005 — Baseline de versões da fundação

- Data: 2026-10-16.
- Estado: implementada na P02, sujeita à revisão do PR.
- Responsabilidade: implementação técnica dentro da stack autorizada pelo usuário; não altera ADR-001.

## Contexto

A proposta histórica citava Angular 18 e Spring. O usuário determinou Quarkus e Java 26 e autorizou a criação da fundação. Precisávamos de versões concretas, ferramentas de migração e builds reproduzíveis.

## Decisão

Fixar Quarkus 3.40.1, Temurin 26.0.2.1_1, Maven 3.9.16, Angular 22.2.3 e Node 24.16.0 nos manifests e imagens. Usar Flyway para migrações por microsserviço e as extensões Quarkus para telemetria, sem agente Java duplicado. O [inventário](../libs.md) registra demais versões resolvidas.

## Alternativas e trade-offs

Manter Angular 18 conservaria a versão histórica, mas exigiria uma cadeia antiga para um projeto novo. Angular 22 exige Node e TypeScript recentes. Quarkus anterior reduziria mudanças de runtime, porém precisaria de outra validação com Java 26. Versões fixadas favorecem reprodução, mas exigem manutenção explícita de segurança. Imagens de aplicação ainda fixadas por tag podem mudar no registry; scanners de JARs não cobrem toda a infraestrutura.

## Validação e revisão

Os quatro serviços compilaram e passaram testes com Java 26; Angular passou build, testes e navegação. Consulte [evidências P02 — histórico Git](https://github.com/mclovin137/Prospero/blob/bd707fa972aa36c6811fa00d13ad73c3073721c1/docs/evidencias/P02-testes.md). Reavaliar ao mudar Java/Quarkus/Angular, ao surgir incompatibilidade ou vulnerabilidade. Não equivale à aprovação de metas de negócio ou desempenho.
