# Decisões arquiteturais

Os ADRs registram contexto, decisão, alternativas, trade-offs e critérios verificáveis. As decisões abaixo foram aceitas pelo usuário no planejamento e tiveram sua implementação documental autorizada em 2026-10-16. **Aceita não significa implementada.** O andamento está no [roadmap](../roadmap.md).

| ADR | Decisão | Estado |
| --- | --- | --- |
| [ADR-001](ADR-001-arquitetura-backend.md) | Quarkus, Java 26, autonomia, bancos e organização do backend | Aceita |
| [ADR-002](ADR-002-padroes-dominio.md) | DDD, SAGA, outbox, idempotência e Object Calisthenics | Aceita |
| [ADR-003](ADR-003-contratos-api.md) | Versionamento, OpenAPI, DTOs e semântica HTTP | Aceita |
| [ADR-004](ADR-004-testes-planilha.md) | Quando criar planilhas e como testar por API | Aceita |
| [ADR-005](ADR-005-versoes-fundacao.md) | Versões concretas e ferramentas da fundação | Implementada; revisão no PR |

## Relação com a proposta original

Os requisitos RF01–RF10 e RNF01–RNF08 permanecem na [arquitetura original](../../prospero-arquitetura.md). Estes ADRs substituem os seguintes pontos daquela proposta:

- Java 25 e Spring Boot/JDBC → Java 26, Quarkus e JPA.
- Estrutura `rest → actor → service/dao` → REST → Actor com domínio, Helpers puros e repositórios JPA separados.
- Infraestrutura de persistência → quatro containers PostgreSQL, um por serviço; o livro continua em memória, com log Kafka e snapshots.
- Estratégia de testes → inclusão de runner `.xlsx` no projeto frontend, sem eliminar testes internos.
- Organização documental → `docs/rules.md`, `docs/libs.md`, `docs/roadmap.md` e este diretório de ADRs.

## Manutenção

Novos ADRs usam numeração sequencial e título descritivo. Incluem data, estado, responsável pela decisão, contexto, decisão, alternativas, consequências, validação e condição de revisão. Mudança incompatível exige novo ADR que indique qual substitui; não apagar decisões antigas. Questões ainda não decididas devem ser marcadas como pendentes, nunca como decisões aceitas.
