# HLD-002 — Front office

Data: 2026-10-17. Desenho vigente e planejado do contexto, sem nova decisão de produto.

## Responsabilidade e estado

Contexto de negociação/entrada do investidor: RF01/RF02, participação em RF06/RF09/RF10 e coordenação da RF04/RF05. Hoje implementa identidade técnica, consulta de mercado (RF01), registro/consulta de ordens com idempotência (RF02) e validação de lote, tick e túnel (RF03, ADR-011). SAGA, outbox, consulta de trajetória e WebSocket ainda não existem.

Futuro: receber/consultar ordens, persistir estados, orquestrar validação/reserva/execução e expor projeções. Risco pertence ao MO; livro ao ME; contabilidade ao BO. Angular apresenta estados sem coordenar SAGA.

## Stack e organização

Java 26, Quarkus 3.40.1, Maven Wrapper, REST Jackson/Bean Validation, JPA/Hibernate, PostgreSQL 16 e Flyway; cliente Kafka, SmallRye OpenAPI, Health, Micrometer e OpenTelemetry. Versões resolvidas/imagens e overrides: [libs](../libs.md) e ADR-005. Build e imagem próprios, sem artefato compartilhado entre serviços. Não há dependência de implementação de outro contexto.

Organização por funcionalidade sob `br.prospero.fo`: REST → Actor; Actor coordena domínio/Helpers puros e repositórios JPA separados. Adaptadores Kafka futuros chamam casos de uso sem passar pelo REST. Entidades JPA não são DTOs nem domínio. Pacotes vazios não são componentes de negócio implementados.

## Interfaces e implantação atuais

`GET /api/v1/sistema` retorna identidade técnica. Porta externa `8081` em loopback → `8080` no container; `/q/health/ready`, `/q/openapi`, `/q/swagger-ui/` e `/q/metrics` são técnicos. Configuração usa variáveis locais fora do Git. Banco/container exclusivo `postgres-front-office`, banco `prospero_fo`, rede de dados própria. Não acessar tabelas de outro serviço.

`src/main/resources/db/migration/V1__identificar_schema.sql` só identifica o schema. Flyway mantém histórico, sem tabelas financeiras. Build com JDK26: `./mvnw verify` no diretório do serviço; operação pela skill subir-sistema. Processo pode iniciar sem outro MS Java, mas depende de sua infraestrutura para ficar pronto; não confundir autonomia com conclusão de negócio sem participantes.

## Fluxo futuro e dados

Receber DTO → validar estrutura → Actor registra ordem e intenção de publicação na mesma transação local → publisher recuperável Kafka → respostas/eventos avançam a máquina de estados persistida. RF02a implementada conforme [ADR-010](../adrs/ADR-010-ordens-contas-idempotencia.md): `POST/GET /api/v1/ordens` e `/api/v1/ordens-oco`, `202` com `Location`, idempotência `(conta, X-Idempotency-Key)` com hash na mesma transação, contas sintéticas por `sub`, estado único `RECEBIDA`. A intenção de publicação (outbox) entra na RF04, quando houver consumidor; até lá o registro é apenas local.

RF01 implementada conforme [ADR-009](../adrs/ADR-009-projecao-mercado-fo.md): projeção de leitura no PostgreSQL do FO (`snapshot_mercado`, uma linha JSONB por namespace/ticker), consulta `GET /api/v1/ativos` e `/api/v1/ativos/{ticker}/livro` e preparação sintética local por namespace, todas com JWT. Redis permanece opção de cache futuro, sem uso atual. Atualização por negócios entra na RF06. Negócio não usa HTTP entre MS. Nomes de comandos, envelopes e tópicos dependem do catálogo transversal do HLD-001.

## Recuperação, riscos e pendências

Persistir SAGA, timeout e próximos passos; definir janela de publicação, reentrega e reinício sem duplicar submissão. Antes de compensar, conhecer resultado efetivo: negócio executado exige recuperação para frente, sem desfazê-lo. Definir concorrência de cancelamento/execução/timeout e idempotência dos comandos.

Pendentes: transições após `RECEBIDA` (RF03–RF05); expiração efetiva da validade Dia (sessão já definida no ADR-010); ativação de OCO e dimensionamento após parciais; correlação ordem/outbox ausente na tabela histórica; bootstrap de projeção por eventos, sem acessar bancos alheios. Contas sintéticas e escopos definidos no ADR-010; identidade real permanece futura.

Aceite futuro: duplicação, reentrega, indisponibilidade e consulta de estados consistentes, WebSocket/reconexão e planilha com polling limitado. [Roadmap FO](../../front-office/ROADMAP.md); RNFs especialmente RNF05–RNF08. Não há meta própria nova de latência.

## Modelo de dados de referência

Dicionário migrado da proposta histórica. **Superado nas tabelas já implementadas**: `ativo` (V2, ADR-009) e `conta`, `ordem`, `idempotencia_envio`, `limite_ordens` (V3, ADR-010) e a base do túnel `preco_base_tunel`/`origem_base` com OMEGA3 em lote 100/tick 0,05 (V4, ADR-011) usam `NUMERIC(12,2)` para preços; as migrações Flyway são a fonte. O restante é planejado e incompleto, não schema aprovado. Tipos bigint não definem escala financeira; ausência de marcação não decide nulabilidade/índice/constraint. Referências a IDs externos não criam FK entre bancos. `ativo` representa referência local a distribuir por contrato, sem tabela compartilhada. Refinar antes de migrações.

### `ativo` — referência preliminar
| Coluna | Tipo | Chave |
| --- | --- | --- |
| `ticker` | `varchar(12)` | PK |
| `tick` | `bigint` | — |
| `lote_minimo` | `bigint` | — |
| `tunel_pct` | `int` | — |
| `negociavel` | `boolean` | — |

### `ordem` — referência preliminar
| Coluna | Tipo | Chave |
| --- | --- | --- |
| `id` | `uuid` | PK |
| `conta_id` | `varchar(32)` | — |
| `ativo` | `varchar(12)` | FK para referência local de ativo |
| `lado` | `char(1)` | — |
| `tipo` | `varchar(12)` | — |
| `validade` | `varchar(4)` | — |
| `quantidade` | `bigint` | — |
| `executada` | `bigint` | — |
| `preco_limite` | `bigint` | — |
| `gatilho` | `bigint` | — |
| `oco_par` | `uuid` | — |
| `estado` | `varchar(20)` | — |
| `versao` | `int` | — |

### `outbox` — referência preliminar
| Coluna | Tipo | Chave |
| --- | --- | --- |
| `id` | `bigserial` | PK |
| `topico` | `varchar(64)` | — |
| `chave` | `varchar(64)` | — |
| `tipo` | `varchar(48)` | — |
| `payload` | `jsonb` | — |
| `publicado_em` | `timestamptz` | — |

## Histórico de ordens — conservação planejada

A avaliação de arquivamento é válida com condições: FO continua proprietário do histórico de ordens/SAGA e não consulta tabelas BO. Mover somente ordens elegíveis encerradas, preservando pendências, correlações, reentrega/deduplicação e recuperação. Não há tabela financeira ou volume medido para adotar partições/arquivo frio hoje.

A política proposta no [HLD-005](HLD-005-back-office.md#histórico-frio-e-fechamento-por-conjuntos--avaliação-para-rf08) exige arquivo verificado/restaurável, retenção explícita, controle de acesso, consulta por conta/período paginada e falha/recuperação declaradas. Não apagar a verdade de negócio nem responder vazio porque o dado saiu da área quente; restauração temporária versus leitura de arquivo permanecem alternativas. Estado corrente de uma ordem pode mudar conforme seu ciclo, mas arquivamento não autoriza alterar silenciosamente negócios/razão ou apagar sua trajetória.

## Segurança, observabilidade e testes

Seguir ADR-003 para DTOs, versão, Swagger, status e erros; contratos de negócio ainda precisam de definição. Autenticação e autorização por conta/papel são pendências, sem RBAC presumido. Não registrar credenciais ou payloads sensíveis. Entradas exigem limites e invariantes; o endpoint técnico não comprova proteção do fluxo financeiro.

Fundação: saúde de banco/Kafka, métricas técnicas, logs com correlação e exportação OTLP. Não afirmar trace financeiro completo, métricas de domínio ou capacidade comprovada. Testes atuais usam JUnit/REST Assured/ArchUnit; perfil de contrato desativa banco/Kafka, smoke Compose exercita dependências reais. Casos futuros via API/planilha conforme ADR-004 complementam invariantes internas e testes de concorrência/recuperação.

## Decisões e rastreabilidade

[ADR-001](../adrs/ADR-001-arquitetura-backend.md) define fronteiras/backend; [ADR-002](../adrs/ADR-002-padroes-dominio.md), padrões; [ADR-003](../adrs/ADR-003-contratos-api.md), contratos; [ADR-004](../adrs/ADR-004-testes-planilha.md), testes; [ADR-005](../adrs/ADR-005-versoes-fundacao.md), stack. [PRD-001](../prds/PRD-001-simulador-prospero.md) é a fonte dos requisitos. Este HLD detalha o [ecossistema](HLD-001-arquitetura-prospero.md), sem alterar decisões.
