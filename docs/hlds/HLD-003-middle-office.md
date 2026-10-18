# HLD-003 — Middle office

Data: 2026-10-17. Desenho vigente e planejado do contexto, sem nova decisão de produto.

## Responsabilidade e estado

Contexto de risco: validação RF03, reservas/limites RF04, consumo/liberação RF05/RF10 e efeitos de liquidação RF08. Desde a RF04a ([ADR-012](../adrs/ADR-012-risco-reservas-saga.md)): consumidor de `risco.comandos.v1`, cálculo puro da reserva por tipo (`risco/helper/CalculoReserva`), linhas de controle `conta_risco`/`custodia_risco` com UPDATE condicional, `decisao_risco` por intenção, `reserva` e outbox de `risco.resultados.v1` na mesma transação. Consumo/liberação (RF05/RF10) e efeitos de liquidação continuam pendentes.

A P05 entrega somente `decimal/dominio/ValorEscalado`, `RegraDeSinal` e `decimal/helper/ConversaoDecimalExata`: núcleo técnico exato, não um tipo financeiro com escala/tick aprovado. P05 foi integrada ao main pelo PR #3 em 2026-10-17. RF03 continua planejada.

## Stack e organização

Java 26, Quarkus 3.40.1, Maven Wrapper, REST Jackson/Bean Validation, JPA/Hibernate, PostgreSQL 16 e Flyway; cliente Kafka, SmallRye OpenAPI, Health, Micrometer e OpenTelemetry. Versões resolvidas/imagens e overrides: [libs](../libs.md) e ADR-005. Build e imagem próprios, sem artefato compartilhado entre serviços. Não há dependência de implementação de outro contexto.

Organização por funcionalidade sob `br.prospero.mo`: REST → Actor; Actor coordena domínio/Helpers puros e repositórios JPA separados. Adaptadores Kafka futuros chamam casos de uso sem passar pelo REST. Entidades JPA não são DTOs nem domínio. Pacotes vazios não são componentes de negócio implementados.

## Interfaces e implantação atuais

`GET /api/v1/sistema` retorna identidade técnica. Desde a RF04b: `GET /api/v1/risco/carteira` (JWT `risco:leitura`, conta do `sub`) e `PUT /api/v1/preparacoes-risco/contas/{conta}` (JWT `risco:preparacao`, só CONTA-002/003, desligado fora do Compose local), com Problem Details iguais aos do FO ([ADR-012](../adrs/ADR-012-risco-reservas-saga.md)). Porta externa `8082` em loopback → `8080` no container; `/q/health/ready`, `/q/openapi`, `/q/swagger-ui/` e `/q/metrics` são técnicos. Configuração usa variáveis locais fora do Git. Banco/container exclusivo `postgres-middle-office`, banco `prospero_mo`, rede de dados própria. Não acessar tabelas de outro serviço.

`V1__identificar_schema.sql` identifica o schema; `V2__criar_risco_e_reservas.sql` (RF04a) cria parâmetros dos ativos, carteiras sintéticas semeadas, decisões, reservas, outbox e diagnóstico de mensagens inválidas. Build com JDK26: `./mvnw verify` no diretório do serviço; operação pela skill subir-sistema. Processo pode iniciar sem outro MS Java, mas depende de sua infraestrutura para ficar pronto; não confundir autonomia com conclusão de negócio sem participantes.

## Proveniência e limites de reuso

A P05 adapta conceitos/casos do [MinervaFinancas no commit 34fc98a](https://github.com/Cris042/MinervaFinancas/tree/34fc98a6ac47f2ffc2bf23f055a3fd9ac15c7530), sem licença explícita encontrada: implementação autoral, sem copiar/redistribuir arquivos; atribuição não substitui licença. O núcleo técnico MO valida também no construtor, limita precisão/escala antes da expansão decimal, preserva zeros finais sem perda e rejeita fração não representável/overflow, sem arredondamento. Não adota as escalas financeiras 2/2/8 do doador, biblioteca compartilhada ou nova dependência.

Saldo de caixa não equivale a reserva intradiária; transação monolítica e serialização SQLite não comprovam concorrência PostgreSQL nem coordenação entre nossos bancos. O doador não fornece SAGA/outbox/reservas adequadas ao fluxo. Short/PnL e calendário dependem das RF07/RF08, conforme HLD-005; P05 não conclui RF03/RF04.

## Fluxo futuro e dados

Comando recebido por evento → validar parâmetros e limites → transação local verifica/atualiza recurso e registra reserva com confirmação recuperável → publica resultado. Nenhuma leitura síncrona de conta/posição em banco alheio. Fonte, atualização e ordenação da posição usada no risco precisam de contrato por evento.

Objetos financeiros e regras puras devem explicitar escala, sinal, arredondamento e overflow antes do código. Estratégias independentes de túnel, máximo, poder de compra e exposição são proposta de organização; não criar engine genérica. Snapshot de risco não substitui proteção contra concorrência na transação.

## Políticas alteráveis sem deploy — proposta em avaliação

O usuário pediu avaliar DSL de risco com lexer, parser por precedência, tipos, otimização e VM de pilha. [ADR-008](../adrs/ADR-008-politicas-risco.md) compara parâmetros tipados/versionados com estratégias Java, CEL Java restrito e DSL autoral. **Nenhum foi implementado/adotado para risco dinâmico.** A recomendação inicial é parametrizar limites quando fórmulas são estáveis; compilador próprio requer necessidade de combinações dinâmicas ou objetivo didático aceito. A orientação atual de evitar engine genérica permanece válida enquanto a proposta não for adotada.

Se escolhida a DSL: administração autorizada compila/valida fora do caminho da ordem, publica versão imutável e ativa conjunto completo atomicamente. MO avalia programa puro sobre fatos locais consistentes; Actor mantém reserva/deduplicação/decisão-versionada/outbox na transação local. Aprovação da regra não substitui proteção contra duas ordens concorrentes. Reentrega recupera resultado anterior; rollback de política vale para novas decisões. Fontes/tipos de variáveis como patrimônio/VaR continuam indefinidos e não são novas regras de produto aprovadas.

Gramática/funções enumeradas, aritmética exata sem double, curto-circuito, limites de compilação/VM e ausência de I/O/reflexão/loops são critérios de avaliação. Não aceitar bytecode externo; verificar instruções/saltos/pilha. Erro, dado obrigatório ausente e orçamento excedido nunca autorizam ordem por padrão. API administrativa, papéis, vigência/distribuição de versões, fatos e política de falhas serão refinados antes do código. Não há prova de execução em microssegundos. O ADR detalha pipeline, exemplos, alternativas e testes futuros.

## Limites concorrentes e write skew — avaliação para RF04

**Parecer: válido com ressalvas.** Duas transações podem ler disponível 100 e inserir reservas distintas de 70; juntas violam o limite se ambas confirmarem. É write skew de uma invariante agregada, diferente de sobrescrever a mesma linha (lost update). Snapshot isolation/`REPEATABLE READ` PostgreSQL não elimina essa anomalia. Nenhuma tabela/reserva financeira existe hoje: é risco de desenho, não defeito reproduzido. [Isolamento PostgreSQL 16](https://www.postgresql.org/docs/16/transaction-iso.html).

Recomendação inicial a validar: linha local de controle por conta/recurso com atualização condicional que confere suficiência e altera disponível/reservado, na mesma transação de reserva, decisão/versionamento, deduplicação e outbox. Todos os escritores — admissão, consumo, liberação e alteração de limite/fatos pertinentes — preservam a mesma invariante. Não materializar sem definir quais limites incluem conta, ativo, posição e exposição vendida.

| Alternativa | Condição de validade |
| --- | --- |
| `SELECT … FOR UPDATE` na conta/guardião | Protocolo de todos os escritores; em `READ COMMITTED`, adquirir guardião primeiro e consultar agregado em comando posterior com leitura atual, sem reutilizar estado antigo do ORM |
| Linha materializada comum | Teste de suficiência e mutação atômicos; controle e reserva confirmam/revertem juntos |
| `SERIALIZABLE` | Transações participantes usam o protocolo/isolações necessários; tratar abortos e repetir a transação inteira, sem efeitos externos antecipados |

**Lock sozinho não renova snapshot.** Em `REPEATABLE READ`, bloquear guardião sem modificá-lo não torna recente um agregado lido no snapshot antigo. Essa ressalva é inferência das regras de snapshot/locking documentadas; não anunciar segurança apenas por adicionar `FOR UPDATE`. Bloquear só a ordem ou reservas existentes não protege novas inserções/conjunto vazio; `SUM(...) FOR UPDATE` não é mecanismo válido para bloquear o agregado. Conta ausente não fornece guardião: rejeitar ou definir criação única/idempotente. Locks de múltiplos recursos têm ordem determinística e transações curtas, sem esperar Kafka/outro MS. [Locks](https://www.postgresql.org/docs/16/explicit-locking.html), [restrições SELECT](https://www.postgresql.org/docs/16/sql-select.html).

`40001` exige rollback/repetição de toda a transação, inclusive leituras e decisão; `40P01` pode ter repetição limitada após rollback. Definir tentativas e espera com dispersão, identidade idempotente e resolução de commit desconhecido. Não tratar qualquer `23505` como transitório. [Falhas de serialização](https://www.postgresql.org/docs/16/mvcc-serialization-failure-handling.html).

Teste futuro em PostgreSQL 16 real: duas conexões/transações, barreira após leitura sem proteção para reproduzir 140 sobre 100; depois proteção permite somente uma reserva de 70, a outra reavalia/rejeita ou aborta/repetirá e rejeitará. Controlar aquisição sem barreira que exija dois donos do mesmo lock. Inverter vencedor, testar rollback, ausência/conjunto vazio, contas independentes, todos os escritores e reentrega; conferir invariante por nova leitura após commits. O perfil `%test` atual desativa datasource: seus testes não comprovam essa concorrência. Nenhum cenário foi executado nesta entrega.

## Recuperação, riscos e pendências

Definir atomicidade entre reserva e evento de confirmação, deduplicação e consumo/liberação em execução parcial, preço melhor, IOC, rejeição e cancelamento. Proteger limites compartilhados contra duas ordens concorrentes; escolher update condicional/lock/versão/isolamento conforme invariante e teste, sem prescrever solução não refinada.

Reservas de Market/Stop/Stop Limit/short, proteção de posição/exposição e limite por ordem são pendentes. Timeout não libera automaticamente recursos quando execução ainda pode ocorrer. Separar disponível/reservado/liquidado; efeitos BO chegam por eventos idempotentes.

Lote, tick e túnel de ±10% são validados no FO desde a RF03 ([ADR-011](../adrs/ADR-011-validacao-preco-ordens.md)); o MO pode revalidar como defesa em profundidade na RF04. Pendentes no MO: calendário de negociação e regras de risco. Testar limites, concorrência, reentrega e compensação; núcleo decimal tem evidência [P05 — histórico Git](https://github.com/mclovin137/Prospero/blob/bd707fa972aa36c6811fa00d13ad73c3073721c1/docs/evidencias/P05-testes.md). [Roadmap MO](../../middle-office/ROADMAP.md); RNF01/RNF05/RNF06 e operação RNF07/RNF08.

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

Seguir ADR-003 para DTOs, versão, Swagger, status e erros; contratos de negócio ainda precisam de definição. Autenticação: JWT local de audiência `prospero-middle-office` (RF04b); identidade real e RBAC continuam pendentes. Não registrar credenciais ou payloads sensíveis. Entradas exigem limites e invariantes; o endpoint técnico não comprova proteção do fluxo financeiro.

Fundação: saúde de banco/Kafka, métricas técnicas, logs com correlação e exportação OTLP. Não afirmar trace financeiro completo, métricas de domínio ou capacidade comprovada. Testes atuais usam JUnit/REST Assured/ArchUnit; perfil de contrato desativa banco/Kafka, smoke Compose exercita dependências reais. Casos futuros via API/planilha conforme ADR-004 complementam invariantes internas e testes de concorrência/recuperação.

## Decisões e rastreabilidade

[ADR-001](../adrs/ADR-001-arquitetura-backend.md) define fronteiras/backend; [ADR-002](../adrs/ADR-002-padroes-dominio.md), padrões; [ADR-003](../adrs/ADR-003-contratos-api.md), contratos; [ADR-004](../adrs/ADR-004-testes-planilha.md), testes; [ADR-005](../adrs/ADR-005-versoes-fundacao.md), stack. [PRD-001](../prds/PRD-001-simulador-prospero.md) é a fonte dos requisitos. Este HLD detalha o [ecossistema](HLD-001-arquitetura-prospero.md), sem alterar decisões.
