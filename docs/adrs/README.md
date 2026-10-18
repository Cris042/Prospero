# Decisões arquiteturais

Os ADRs registram contexto, decisão, alternativas, trade-offs e critérios verificáveis. Os ADRs 001–004 foram aceitos pelo usuário no planejamento, com implementação documental autorizada em 2026-10-16; os demais registram seus estados e evidências nas próprias páginas. **Aceita não significa implementada.** O andamento está no [roadmap](../roadmap.md).

| ADR | Decisão | Estado |
| --- | --- | --- |
| [ADR-001](ADR-001-arquitetura-backend.md) | Quarkus, Java 26, autonomia, bancos e organização do backend | Aceita |
| [ADR-002](ADR-002-padroes-dominio.md) | DDD, SAGA, outbox, idempotência e Object Calisthenics | Aceita |
| [ADR-003](ADR-003-contratos-api.md) | Versionamento, OpenAPI, DTOs e semântica HTTP | Aceita |
| [ADR-004](ADR-004-testes-planilha.md) | Quando criar planilhas e como testar por API | Aceita |
| [ADR-005](ADR-005-versoes-fundacao.md) | Versões concretas e ferramentas da fundação | Implementada; revisão no PR |
| [ADR-006](ADR-006-api-carga.md) | Linguagem e isolamento da API de carga; Go escolhido | Go aceito; stdlib/executor restrito em validação |
| [ADR-007](ADR-007-coletor-gc.md) | G1 como baseline; comparação Parallel/ZGC e medição de pausas | Proposta; sem mudança de runtime |
| [ADR-008](ADR-008-politicas-risco.md) | Parâmetros versionados versus DSL de risco no MO | Proposta; sem implementação |
| [ADR-009](ADR-009-projecao-mercado-fo.md) | Projeção de mercado no PostgreSQL do FO e JWT local da RF01 | Aceita e implementada |
| [ADR-010](ADR-010-ordens-contas-idempotencia.md) | Ordens, contas sintéticas, idempotência e 202 da RF02 | Aceita e implementada (RF02a e RF02c) |
| [ADR-011](ADR-011-validacao-preco-ordens.md) | Lote, tick e túnel de ±10% no FO (RF03) | Aceita e implementada |

## Relação com a proposta original

Os requisitos RF01–RF10 e RNF01–RNF08 estão no [PRD-001](../prds/PRD-001-simulador-prospero.md) e o desenho vigente no [HLD-001](../hlds/HLD-001-arquitetura-prospero.md). A proposta anterior foi removida por solicitação do usuário, preservada no [histórico Git](https://github.com/mclovin137/Prospero/blob/c84aea222a17c28422e0050df9f477b487980470/prospero-arquitetura.md). Estes ADRs substituíram os seguintes pontos daquela proposta:

- Java 25 e Spring Boot/JDBC → Java 26, Quarkus e JPA.
- Estrutura `rest → actor → service/dao` → REST → Actor com domínio, Helpers puros e repositórios JPA separados.
- Infraestrutura de persistência → quatro containers PostgreSQL, um por serviço; o livro continua em memória, com log Kafka e snapshots.
- Estratégia de testes → inclusão de runner `.xlsx` no projeto frontend, sem eliminar testes internos.
- Organização documental → `docs/rules.md`, `docs/libs.md`, `docs/roadmap.md` e este diretório de ADRs.

## Manutenção

Novos ADRs usam numeração sequencial e título descritivo. Incluem data, estado, responsável pela decisão, contexto, decisão, alternativas, consequências, validação e condição de revisão. Mudança incompatível exige novo ADR que indique qual substitui; não apagar decisões antigas. Questões ainda não decididas devem ser marcadas como pendentes, nunca como decisões aceitas.
