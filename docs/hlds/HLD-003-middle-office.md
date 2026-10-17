# HLD-003 — Middle office

Data: 2026-10-17. Desenho vigente e planejado do contexto, sem nova decisão de produto.

## Responsabilidade e estado

Contexto de risco: validação RF03, reservas/limites RF04, consumo/liberação RF05/RF10 e efeitos de liquidação RF08. A fundação tem identidade e infraestrutura; regras financeiras, reserva e consumidores Kafka ainda não estão implementados.

Nesta branch, P05 entrega somente `decimal/dominio/ValorEscalado`, `RegraDeSinal` e `decimal/helper/ConversaoDecimalExata`: núcleo técnico exato, não um tipo financeiro com escala/tick aprovado. P05 está no PR #3 aguardando merge; sua disponibilidade depende da branch. RF03 continua planejada.

## Stack e organização

Java 26, Quarkus 3.40.1, Maven Wrapper, REST Jackson/Bean Validation, JPA/Hibernate, PostgreSQL 16 e Flyway; cliente Kafka, SmallRye OpenAPI, Health, Micrometer e OpenTelemetry. Versões resolvidas/imagens e overrides: [libs](../libs.md) e ADR-005. Build e imagem próprios, sem artefato compartilhado entre serviços. Não há dependência de implementação de outro contexto.

Organização por funcionalidade sob `br.prospero.mo`: REST → Actor; Actor coordena domínio/Helpers puros e repositórios JPA separados. Adaptadores Kafka futuros chamam casos de uso sem passar pelo REST. Entidades JPA não são DTOs nem domínio. Pacotes vazios não são componentes de negócio implementados.

## Interfaces e implantação atuais

`GET /api/v1/sistema` retorna identidade técnica. Porta externa `8082` em loopback → `8080` no container; `/q/health/ready`, `/q/openapi`, `/q/swagger-ui/` e `/q/metrics` são técnicos. Configuração usa variáveis locais fora do Git. Banco/container exclusivo `postgres-middle-office`, banco `prospero_mo`, rede de dados própria. Não acessar tabelas de outro serviço.

`src/main/resources/db/migration/V1__identificar_schema.sql` só identifica o schema. Flyway mantém histórico, sem tabelas financeiras. Build com JDK26: `./mvnw verify` no diretório do serviço; operação pela skill subir-sistema. Processo pode iniciar sem outro MS Java, mas depende de sua infraestrutura para ficar pronto; não confundir autonomia com conclusão de negócio sem participantes.

## Fluxo futuro e dados

Comando recebido por evento → validar parâmetros e limites → transação local verifica/atualiza recurso e registra reserva com confirmação recuperável → publica resultado. Nenhuma leitura síncrona de conta/posição em banco alheio. Fonte, atualização e ordenação da posição usada no risco precisam de contrato por evento.

Objetos financeiros e regras puras devem explicitar escala, sinal, arredondamento e overflow antes do código. Estratégias independentes de túnel, máximo, poder de compra e exposição são proposta de organização; não criar engine genérica. Snapshot de risco não substitui proteção contra concorrência na transação.

## Recuperação, riscos e pendências

Definir atomicidade entre reserva e evento de confirmação, deduplicação e consumo/liberação em execução parcial, preço melhor, IOC, rejeição e cancelamento. Proteger limites compartilhados contra duas ordens concorrentes; escolher update condicional/lock/versão/isolamento conforme invariante e teste, sem prescrever solução não refinada.

Reservas de Market/Stop/Stop Limit/short, proteção de posição/exposição e limite por ordem são pendentes. Timeout não libera automaticamente recursos quando execução ainda pode ocorrer. Separar disponível/reservado/liquidado; efeitos BO chegam por eventos idempotentes.

Pendentes também lote/tick, túnel ±10% para ordem sem limite, referência ausente/defasada e calendário de negociação. Testar limites, concorrência, reentrega e compensação; núcleo decimal tem evidência [P05](../evidencias/P05-testes.md). [Roadmap MO](../../middle-office/ROADMAP.md); RNF01/RNF05/RNF06 e operação RNF07/RNF08.

## Modelo de dados de referência

Dicionário migrado da proposta histórica. **Planejado e incompleto**, não schema aprovado nem tabelas existentes. Tipos bigint não definem escala financeira; ausência de marcação não decide nulabilidade/índice/constraint. Referências a IDs externos não criam FK entre bancos. `ativo` representa referência local a distribuir por contrato, sem tabela compartilhada. Refinar antes de migrações.

### `ativo` — referência preliminar
| Coluna | Tipo | Chave |
| --- | --- | --- |
| `ticker` | `varchar(12)` | PK |
| `tick` | `bigint` | — |
| `lote_minimo` | `bigint` | — |
| `tunel_pct` | `int` | — |
| `negociavel` | `boolean` | — |

### `conta_risco` — referência preliminar
| Coluna | Tipo | Chave |
| --- | --- | --- |
| `conta_id` | `varchar(32)` | PK |
| `saldo` | `bigint` | — |
| `reservado` | `bigint` | — |
| `limite_vendido` | `bigint` | — |

### `reserva` — referência preliminar
| Coluna | Tipo | Chave |
| --- | --- | --- |
| `ordem_id` | `uuid` | PK; ID da ordem externa, sem FK entre serviços |
| `conta_id` | `varchar(32)` | FK local para `conta_risco` |
| `valor` | `bigint` | — |
| `estado` | `varchar(12)` | — |

## Segurança, observabilidade e testes

Seguir ADR-003 para DTOs, versão, Swagger, status e erros; contratos de negócio ainda precisam de definição. Autenticação e autorização por conta/papel são pendências, sem RBAC presumido. Não registrar credenciais ou payloads sensíveis. Entradas exigem limites e invariantes; o endpoint técnico não comprova proteção do fluxo financeiro.

Fundação: saúde de banco/Kafka, métricas técnicas, logs com correlação e exportação OTLP. Não afirmar trace financeiro completo, métricas de domínio ou capacidade comprovada. Testes atuais usam JUnit/REST Assured/ArchUnit; perfil de contrato desativa banco/Kafka, smoke Compose exercita dependências reais. Casos futuros via API/planilha conforme ADR-004 complementam invariantes internas e testes de concorrência/recuperação.

## Decisões e rastreabilidade

[ADR-001](../adrs/ADR-001-arquitetura-backend.md) define fronteiras/backend; [ADR-002](../adrs/ADR-002-padroes-dominio.md), padrões; [ADR-003](../adrs/ADR-003-contratos-api.md), contratos; [ADR-004](../adrs/ADR-004-testes-planilha.md), testes; [ADR-005](../adrs/ADR-005-versoes-fundacao.md), stack. [PRD-001](../prds/PRD-001-simulador-prospero.md) é a fonte dos requisitos. Este HLD detalha o [ecossistema](HLD-001-arquitetura-prospero.md), sem alterar decisões.
