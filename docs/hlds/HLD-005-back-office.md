# HLD-005 — Back office

Data: 2026-10-17. Desenho vigente e planejado do contexto, sem nova decisão de produto.

## Responsabilidade e estado

Contexto de pós-negociação: RF07/RF08 e eventos de trajetória RF09. Fundação técnica existe; não há registro de negócio, cálculo de posição, razão ou liquidação implementados. PnL e posição pertencem ao BO, não ao núcleo de risco MO.

Futuro: consumir execuções, registrar negócio/razão/posição atomicamente, reconstruir projeções, atualizar resultado por cotação e simular caixa/custódia D+2. Não acessa tabelas FO/MO/ME; participantes e dados necessários devem chegar em contratos.

## Stack e organização

Java 26, Quarkus 3.40.1, Maven Wrapper, REST Jackson/Bean Validation, JPA/Hibernate, PostgreSQL 16 e Flyway; cliente Kafka, SmallRye OpenAPI, Health, Micrometer e OpenTelemetry. Versões resolvidas/imagens e overrides: [libs](../libs.md) e ADR-005. Build e imagem próprios, sem artefato compartilhado entre serviços. Não há dependência de implementação de outro contexto.

Organização por funcionalidade sob `br.prospero.bo`: REST → Actor; Actor coordena domínio/Helpers puros e repositórios JPA separados. Adaptadores Kafka futuros chamam casos de uso sem passar pelo REST. Entidades JPA não são DTOs nem domínio. Pacotes vazios não são componentes de negócio implementados.

## Interfaces e implantação atuais

`GET /api/v1/sistema` retorna identidade técnica. Porta externa `8084` em loopback → `8080` no container; `/q/health/ready`, `/q/openapi`, `/q/swagger-ui/` e `/q/metrics` são técnicos. Configuração usa variáveis locais fora do Git. Banco/container exclusivo `postgres-back-office`, banco `prospero_bo`, rede de dados própria. Não acessar tabelas de outro serviço.

`src/main/resources/db/migration/V1__identificar_schema.sql` só identifica o schema. Flyway mantém histórico, sem tabelas financeiras. Build com JDK26: `./mvnw verify` no diretório do serviço; operação pela skill subir-sistema. Processo pode iniciar sem outro MS Java, mas depende de sua infraestrutura para ficar pronto; não confundir autonomia com conclusão de negócio sem participantes.

## Processamento e invariantes futuros

ID determinístico de negócio permite deduplicação atômica com inserção, lançamentos e atualização de posição. Uma inserção ignorada por conflito não pode repetir efeitos. IDs e ordenação precisam preservar os dois lados/contas; o modelo antigo só tem IDs das ordens e não define como BO recebe contas, lacuna anterior à migração.

Negócios e razão têm histórico append-only; correções exigem estratégia explícita, sem alterar silenciosamente negócio executado. Posição é projeção reconstruível: quantidade com sinal, aumento altera preço médio, redução realiza resultado e virada abre lado novo. Arredondamento e limites ainda precisam de decisão; o cálculo do Minerva não cobre short/virada nem o contrato de resultado realizado; a [proveniência e restrição de cópia](HLD-003-middle-office.md#proveniência-e-limites-de-reuso) continuam aplicáveis. Fluxo acumulado de compras/vendas não equivale a PnL realizado. Calendário só de dias da semana não resolve feriados/D+2; não importar sua regra como decisão local.

## Liquidação, recuperação e pendências

D+2 precisa de calendário/dias úteis/feriados, estados, falhas, conciliação e relógio controlável por API de teste. Diferenciar disponível, reservado e liquidado e publicar efeitos idempotentes para MO. Repetir job/evento após reinício não pode liquidar novamente; resultado desconhecido exige reconciliação segura, sem assumir falha e refazer efeitos.

Resolver conflito conceitual entre append-only e `lancamento.estado`; estados de liquidação não estão completamente modelados. Definir projeção não realizada por cotação/idade, passagem de ordenação por ativo para conta, participantes, IDs, e fechamento de caixa/custódia. O dicionário não define toda a contabilidade nem um scheduler adotado.

Testes futuros: long/short, aumento/redução/zeragem/virada, idempotência, transação negócio/razão/posição, conservação de caixa/custódia, D+2 sem aguardar dias reais, falha/reinício e conciliação. [Roadmap BO](../../back-office/ROADMAP.md); RNF01/RNF05/RNF06 e observabilidade/operação RNF07/RNF08.

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

### `negocio` — referência preliminar
| Coluna | Tipo | Chave |
| --- | --- | --- |
| `id` | `varchar(32)` | PK determinística |
| `ativo` | `varchar(12)` | — |
| `preco` | `bigint` | — |
| `quantidade` | `bigint` | — |
| `ordem_compra` | `uuid` | — |
| `ordem_venda` | `uuid` | — |
| `agressor` | `char(1)` | — |
| `data_liquidacao` | `date` | — |

### `lancamento` — referência preliminar
| Coluna | Tipo | Chave |
| --- | --- | --- |
| `id` | `bigserial` | PK |
| `negocio_id` | `varchar(32)` | FK local para `negocio` |
| `conta_contabil` | `varchar(32)` | — |
| `debito` | `bigint` | — |
| `credito` | `bigint` | — |
| `estado` | `varchar(12)` | — |

### `posicao` — referência preliminar
| Coluna | Tipo | Chave |
| --- | --- | --- |
| `conta_id` | `varchar(32)` | PK composta |
| `ativo` | `varchar(12)` | PK composta |
| `quantidade` | `bigint` | — |
| `preco_medio` | `bigint` | — |
| `pl_realizado` | `bigint` | — |

## Segurança, observabilidade e testes

Seguir ADR-003 para DTOs, versão, Swagger, status e erros; contratos de negócio ainda precisam de definição. Autenticação e autorização por conta/papel são pendências, sem RBAC presumido. Não registrar credenciais ou payloads sensíveis. Entradas exigem limites e invariantes; o endpoint técnico não comprova proteção do fluxo financeiro.

Fundação: saúde de banco/Kafka, métricas técnicas, logs com correlação e exportação OTLP. Não afirmar trace financeiro completo, métricas de domínio ou capacidade comprovada. Testes atuais usam JUnit/REST Assured/ArchUnit; perfil de contrato desativa banco/Kafka, smoke Compose exercita dependências reais. Casos futuros via API/planilha conforme ADR-004 complementam invariantes internas e testes de concorrência/recuperação.

## Decisões e rastreabilidade

[ADR-001](../adrs/ADR-001-arquitetura-backend.md) define fronteiras/backend; [ADR-002](../adrs/ADR-002-padroes-dominio.md), padrões; [ADR-003](../adrs/ADR-003-contratos-api.md), contratos; [ADR-004](../adrs/ADR-004-testes-planilha.md), testes; [ADR-005](../adrs/ADR-005-versoes-fundacao.md), stack. [PRD-001](../prds/PRD-001-simulador-prospero.md) é a fonte dos requisitos. Este HLD detalha o [ecossistema](HLD-001-arquitetura-prospero.md), sem alterar decisões.
