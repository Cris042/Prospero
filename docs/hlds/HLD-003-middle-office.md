# HLD-003 — Middle office

Data: 2026-10-17. Desenho vigente e planejado do contexto, sem nova decisão de produto.

## Responsabilidade e estado

Contexto de risco: validação RF03, reservas/limites RF04, consumo/liberação RF05/RF10 e efeitos de liquidação RF08. RF04 implementa consumidor de `risco.comandos.v1`, cálculo puro da reserva, controles condicionais, decisão por intenção, reserva e outbox de resultado na mesma transação. A RF05b acrescenta admissão de Limit/Market simples pelo MO e consumo/liberação por eventos do ME; a RF05c.2 integra novos Stop/Stop Limit simples com ativação persistida, conforme ADR-013; a RF08c acrescenta a expectativa de crédito D+2 e o consumidor de `liquidacao.eventos.v1` (ADR-016, seção abaixo), com emissor BO integrado na RF08d; cancelamento a pedido (RF10) permanece futuro. Estado de validação/PR no roadmap.

A P05 entregou `decimal/dominio/ValorEscalado`, `RegraDeSinal` e `decimal/helper/ConversaoDecimalExata`: núcleo técnico exato integrado pelo PR #3. RF03 foi implementada no FO pelo PR #20; parâmetros financeiros do risco estão no ADR-012 e nas migrações locais.

## Stack e organização

Java 26, Quarkus 3.40.1, Maven Wrapper, REST Jackson/Bean Validation, JPA/Hibernate, PostgreSQL 16 e Flyway; cliente Kafka, SmallRye OpenAPI, Health, Micrometer e OpenTelemetry. Versões resolvidas/imagens e overrides: [libs](../libs.md) e ADR-005. Build e imagem próprios, sem artefato compartilhado entre serviços. Não há dependência de implementação de outro contexto.

Organização por funcionalidade sob `br.prospero.mo`: REST → Actor; Actor coordena domínio/Helpers puros e repositórios JPA separados. Adaptadores Kafka futuros chamam casos de uso sem passar pelo REST. Entidades JPA não são DTOs nem domínio. Pacotes vazios não são componentes de negócio implementados.

## Interfaces e implantação atuais

`GET /api/v1/sistema` retorna identidade técnica. Desde a RF04b: `GET /api/v1/risco/carteira` (JWT `risco:leitura`, conta do `sub`) e `PUT /api/v1/preparacoes-risco/contas/{conta}` (JWT `risco:preparacao`, só CONTA-002/003, desligado fora do Compose local), com Problem Details iguais aos do FO ([ADR-012](../adrs/ADR-012-risco-reservas-saga.md)). Porta externa `8082` em loopback → `8080` no container; `/q/health/ready`, `/q/openapi`, `/q/swagger-ui/` e `/q/metrics` são técnicos. Configuração usa variáveis locais fora do Git. Banco/container exclusivo `postgres-middle-office`, banco `prospero_mo`, rede de dados própria. Não acessar tabelas de outro serviço.

`V1__identificar_schema.sql` identifica o schema; `V2__criar_risco_e_reservas.sql` (RF04a) cria parâmetros dos ativos, carteiras sintéticas semeadas, decisões, reservas, outbox e diagnóstico de mensagens inválidas. Build com JDK26: `./mvnw verify` no diretório do serviço; operação pela skill subir-sistema. Processo pode iniciar sem outro MS Java, mas depende de sua infraestrutura para ficar pronto; não confundir autonomia com conclusão de negócio sem participantes.

## Proveniência e limites de reuso

A P05 adapta conceitos/casos do [MinervaFinancas no commit 34fc98a](https://github.com/Cris042/MinervaFinancas/tree/34fc98a6ac47f2ffc2bf23f055a3fd9ac15c7530), sem licença explícita encontrada: implementação autoral, sem copiar/redistribuir arquivos; atribuição não substitui licença. O núcleo técnico MO valida também no construtor, limita precisão/escala antes da expansão decimal, preserva zeros finais sem perda e rejeita fração não representável/overflow, sem arredondamento. Não adota as escalas financeiras 2/2/8 do doador, biblioteca compartilhada ou nova dependência.

Saldo de caixa não equivale a reserva intradiária; transação monolítica e serialização SQLite não comprovam concorrência PostgreSQL nem coordenação entre nossos bancos. O doador não fornece SAGA/outbox/reservas adequadas ao fluxo. Short/PnL e calendário dependem das RF07/RF08, conforme HLD-005; P05 não conclui RF03/RF04.

## Fluxo e dados

Comando recebido por evento → validar parâmetros e limites → transação local verifica/atualiza recurso e registra reserva com confirmação recuperável → publica resultado. Nenhuma leitura síncrona de conta/posição em banco alheio. Fonte, atualização e ordenação da posição usada no risco precisam de contrato por evento.

Na RF05b, risco versão2 transporta sessão/validade. Admissão determinística e metadados são gravados com a reserva e outbox; v1 legado e Stop/OCO continuam preservados sem admissão. `V3__consumo_matching.sql` amplia reservas com quantidade inicial/restante, preço máximo, ordem/admissão/sessão e estados `ATIVA/PARCIAL/ENCERRADA`, além das tabelas de deduplicação de eventos/negócios. O [contrato conjunto](HLD-004-matching-engine.md#contrato-de-integração-rf05b) fixa os envelopes.

O consumidor aplica os dois lados de cada negócio em uma transação, validando antes dos débitos. Compra reduz o total pelo custo executado, consome o reservado ao preço máximo e devolve a melhoria ao disponível; venda reduz total/reservado da custódia. Terminal valida progresso e libera só o saldo. Não credita ações ao comprador nem caixa ao vendedor em D0; desde a RF08c grava na mesma transação a expectativa de crédito D+2 de cada lado. Conta/ativo/ordem/sessão/correlação, preço, lote e saldo são conferidos; diagnóstico não contém payload. Evento incoerente bloqueia o consumo sem avançar offset; falha transitória não vira descarte por número de tentativas.

Admissão, consumo/liberação e preparação usam guardião de conta em ordem fixa. Preparação recusa409 com qualquer reserva viva, inclusive legada/Stop/OCO/admissão pendente, e preserva fatos de deduplicação. Não apagar reserva para repetir cenário. Recuperação de migração é aditiva ou por cópia privada consistente; não presumir retorno seguro ao binário anterior após execução financeira.

Objetos financeiros e regras puras devem explicitar escala, sinal, arredondamento e overflow antes do código. Estratégias independentes de túnel, máximo, poder de compra e exposição são proposta de organização; não criar engine genérica. Snapshot de risco não substitui proteção contra concorrência na transação.

## Políticas alteráveis sem deploy — proposta em avaliação

O usuário pediu avaliar DSL de risco com lexer, parser por precedência, tipos, otimização e VM de pilha. [ADR-008](../adrs/ADR-008-politicas-risco.md) compara parâmetros tipados/versionados com estratégias Java, CEL Java restrito e DSL autoral. **Nenhum foi implementado/adotado para risco dinâmico.** A recomendação inicial é parametrizar limites quando fórmulas são estáveis; compilador próprio requer necessidade de combinações dinâmicas ou objetivo didático aceito. A orientação atual de evitar engine genérica permanece válida enquanto a proposta não for adotada.

Se escolhida a DSL: administração autorizada compila/valida fora do caminho da ordem, publica versão imutável e ativa conjunto completo atomicamente. MO avalia programa puro sobre fatos locais consistentes; Actor mantém reserva/deduplicação/decisão-versionada/outbox na transação local. Aprovação da regra não substitui proteção contra duas ordens concorrentes. Reentrega recupera resultado anterior; rollback de política vale para novas decisões. Fontes/tipos de variáveis como patrimônio/VaR continuam indefinidos e não são novas regras de produto aprovadas.

Gramática/funções enumeradas, aritmética exata sem double, curto-circuito, limites de compilação/VM e ausência de I/O/reflexão/loops são critérios de avaliação. Não aceitar bytecode externo; verificar instruções/saltos/pilha. Erro, dado obrigatório ausente e orçamento excedido nunca autorizam ordem por padrão. API administrativa, papéis, vigência/distribuição de versões, fatos e política de falhas serão refinados antes do código. Não há prova de execução em microssegundos. O ADR detalha pipeline, exemplos, alternativas e testes futuros.

## Limites concorrentes e write skew — avaliação para RF04

**Parecer: válido com ressalvas.** Duas transações podem ler disponível 100 e inserir reservas distintas de 70; juntas violam o limite se ambas confirmarem. É write skew de uma invariante agregada, diferente de sobrescrever a mesma linha (lost update). Snapshot isolation/`REPEATABLE READ` PostgreSQL não elimina essa anomalia. O MO implementou controles condicionais na RF04; o exemplo abaixo fundamenta a proteção, sem substituir as evidências reais registradas no roadmap/PR. [Isolamento PostgreSQL 16](https://www.postgresql.org/docs/16/transaction-iso.html).

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

Reserva/resultado e reserva/admissão usam transação local/outbox; deduplicação e consumo/liberação da RF05b confirmam com os efeitos. O contrato preserva reentrega, execução parcial, preço melhor, IOC e rejeição; cancelamento solicitado pelo investidor permanece na RF10.

Reservas de Market/Stop/Stop Limit/OCO e limite por ordem foram definidos no ADR-012; short está desabilitado. Admissão de Stop/OCO aguarda RF05c. Timeout não libera automaticamente recursos quando execução ainda pode ocorrer. Separar disponível/reservado/liquidado; efeitos BO chegarão por eventos idempotentes.

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

## Integração de stops — RF05c.2

`V4__registrar_acionamento_stop.sql` expande `reserva` com `preco_gatilho` e `stop_acionado=false`. Sem backfill: decisões antigas não ganham admissão. O MO publica nova admissão de stop em v2 na mesma transação da reserva/outbox e mantém v1 para Market/Limit. Eventos v2 validam causa raiz e correlação alvo/agressor por ativo/sessão; pendência confirma gatilho, acionamento valida tipo executável e persiste o marcador sem mover recursos. Negócio/oferta de stop exige acionamento; Stop Limit conserva limite e Stop conserva teto original, com validade executável IOC. Excesso32 encerra sem acionar; excesso500 rejeita e devolve reserva intacta. Preparação continua recusando reservas vivas.

`V5__progresso_pernas_oco.sql` (RF05c.3b1) cria `reserva_perna` e `reserva.oco`: o grupo continua dono do caixa/custódia e cada perna guarda executada/reduzida/cancelada/acionada. O consumidor v3 confere a perna por `dados.ordem`, consome pelo preço máximo do grupo com troco imediato, não move recursos em redução/captura e libera o restante uma única vez no terminal que deixa o grupo sem perna viva. Na RF05c.3b2 o risco v2 do par (TP Limit DIA + SL Stop/Stop Limit DIA) passa a ser admissível: a mesma transação grava a decisão, o grupo (`oco`, preço máximo = reserva ÷ quantidade, `ordem`/`tipo` nulos) e as duas pernas, e a outbox recebe uma `ORDEM_ADMITIDA` v3. Risco v1 e pares decididos antes continuam sem admissão. RF05c.4a: o consumidor aceita `ORDEM_ENCERRADA{EXPIRADA_DIA}` em v4, com causação determinística do encerramento, liberando o saldo da reserva simples ou o grupo OCO uma vez na última perna viva, e rejeições `SESSAO_ENCERRADA`/`SESSAO_NAO_ENCERRADA` como qualquer rejeição; o marco `SESSAO_ENCERRADA` só é deduplicado. RF05c.4b: feature `sessao` (REST → Actor → repositório JPA) expõe `POST /api/v1/sessoes/{ticker}/{data}/encerramento` com escopo `sessao:encerrar`, flag desligada por padrão, e grava o comando na outbox com `ON CONFLICT` pelo id determinístico.

Implantação coordenada, compatibilidade/replay e recuperação para frente seguem [ADR-013](../adrs/ADR-013-matching-execucao.md#contrato-da-integração-de-stops--rf05c2-2026-10-20). Na RF05c.2 OCO e fechamento integrado ficavam fora (RF05c.3/4); créditos (RF08) e admissão retroativa continuam excluídos. Evidências de transação, dedup, migração e APIs no PR/roadmap.

## Crédito de liquidação D+2 — RF08c

Implementa a parte MO do [ADR-016](../adrs/ADR-016-razao-liquidacao-d2.md), feature `br.prospero.mo.liquidacao` (Kafka → Actor → regras puras `RegrasLiquidacao`/`CreditoEsperado` e helper `EnvelopeLiquidacao` → `RepositorioLiquidacaoJpa`). `V7__expectativa_credito_liquidacao.sql` é aditiva, sem backfill: `liquidacao_expectativa` por (negócio, lado) com conta, recurso, ativo e valor exato (`NUMERIC(18,2)` para caixa, `BIGINT` para custódia), sem data; `liquidacao_aplicada` (id, assinatura) só para créditos aplicados; `liquidacao_aguardando` por (id, assinatura); `liquidacao_recusada` com motivo enumerado e única por posição no tópico, sem payload, conta ou valor.

- **Expectativa:** gravada em `RepositorioMatchingJpa.aplicarNegocio`, na transação dos débitos D0 e sob os locks ordenados das contas: comprador espera a quantidade do ativo em custódia, vendedor espera preço × quantidade em centavos (`RoundingMode.UNNECESSARY`). Autonegociação grava os dois lados para a mesma conta; o robô formador (CONTA-004) não tem tratamento especial. A mesma transação aplica o aguardando idêntico mais antigo do id e recusa os demais (`DIVERGENTE` ou `REPETICAO_DIVERGENTE`).
- **Contrato `LIQUIDACAO_EFETIVADA` v1:** envelope de até 4096 bytes com `id`, `tipo`, `versao`, `origem=back-office`, `causacao` (id do evento `NEGOCIO`), `ocorridoEm`, `traceparent`, `dados{negocio, lado, conta, recurso, ticker, valor, dataLiquidacao}`; valores textuais (caixa `0.00`, custódia inteira positiva ≤ 1.000.000). `id = UUID.nameUUIDFromBytes(UTF-8("liquidacao:" + negocio + ":" + lado))` (UUID v3), mesma derivação a usar no emissor da RF08d. Chave Kafka = `dados.conta`. `dataLiquidacao` só tem o formato conferido; `causacao` é conferida como UUID e não é comparada com a expectativa, que compara apenas conta, recurso, ativo e valor (ADR-016). A assinatura é SHA-256 do envelope canônico.
- **Desfechos por evento** (uma transação, grupo `middle-office-liquidacao`, modo financeiro do `ConsumoKafka`): expectativa idêntica credita total e disponível no mesmo UPDATE por conta (CHECK `disponivel + reservado = total` preservado) e grava a deduplicação; repetição idêntica não tem efeito; sem expectativa, aguarda até o teto `prospero.liquidacao.aguardando.teto` (`MO_LIQUIDACAO_AGUARDANDO_TETO`, padrão 10000) e acima dele recusa `AGUARDANDO_ESGOTADO`; contrato, origem, id derivado e chave incoerentes, divergência e repetição divergente viram recusa persistida. Recusa e aguardando nunca lançam exceção: a partição avança; só falha técnica repete sem descarte e deixa a prontidão `liquidacao-financeira` DOWN até o próximo sucesso.
- **Concorrência:** evento trava a conta informada e um advisory lock transacional do id; o consumo do negócio trava as contas e depois os ids em ordem fixa. Assim um evento com outra conta não escapa da resolução do aguardando. Reserva, consumo, crédito e preparação continuam serializados pelo lock da conta.
- **Preparação local:** `MO_RECUSAR_LIQUIDACAO_PENDENTE` é true por padrão desde a RF08e. Conta com expectativa não creditada recebe 409 `LIQUIDACAO_PENDENTE` sob o lock da conta, antes de qualquer escrita. A CI mantém false apenas na fase histórica D0 (`posicoes`, `cotacao`, `matching`, `risco`), liga a guarda recriando somente MO ao fim das negociações e comprova a recusa sem escrita antes da conciliação D+2. Essa fase histórica não é evidência de proteção contra preparação com pendência.
- **Consulta de crédito RF08e:** `GET /api/v1/risco/liquidacoes/{negocio}` exige audiência MO e `risco:leitura`, conta do subject validada. REST → `ConsultarCreditoActor` → JPA; igualdade parametrizada por negócio/conta e PK (negócio,lado), limite dois lados, sem contraparte. Valor textual exato e `creditado` derivado de `creditada_em`; 200 vazio tanto para alheio quanto ainda não consumido, 400 genérico para identificador inválido, `no-store`. Sem prova de consumo quando vazio; o harness espera a expectativa antes do snapshot. Nenhum token novo no navegador.

- **Observabilidade:** gauges `prospero.liquidacao.aguardando`, `prospero.liquidacao.aguardando.idade.segundos`, `prospero.liquidacao.expectativas.pendentes` e `prospero.liquidacao.recusadas{motivo}`, recalculados do banco a cada 5 s; logs só com partição, offset e motivo. `kafka-init` cria `liquidacao.eventos.v1` com 3 partições e retenção integral, com `--alter` para volumes antigos.

Limites: o teto é verificado por contagem na transação do evento, com um único consumidor por instância; recusas e expectativas creditadas não têm expurgo nesta fatia. Evidências (TDD, `LiquidacaoPostgresIT`, REST Assured) no PR.


## V01b — agendamento da outbox após commit

A implementação em validação do [ADR-018](../adrs/ADR-018-publicacao-outbox-apos-commit.md) antecipa o relay depois do commit de OutboxJpa e a inserção direta do encerramento de sessão. Um callback por transação, sem dados/I/O, apenas solicita execução coalescida. Executor único compartilha publicação, fallback de 200 ms e manutenção a cada aproximadamente 5 s por tempo monotônico, inclusive entre lotes; falha impõe cooldown de 200 ms também contra sinais novos. Perda do sinal/reinício continua recuperada pela outbox persistida.

`PROSPERO_OUTBOX_URGENCIA=false` conserva somente polling para comparação/reversão; não desabilita mensageria ou guardas financeiras. Seleção ordenada, lote de 50 / orçamento de 20 s, ack antes de marcação e reentrega idempotente permanecem. Um publicador concreto recebe Producer/EntityManager para verificar essas fronteiras com PostgreSQL e ack controlado; não é domínio ou biblioteca compartilhada. Estado/evidências no roadmap; ganho de latência depende da medição integrada, sem novo aceite financeiro.

## Prova durável de admissão — contrato RF10c1

Preparação para o cancelamento integrado, ainda sem emissão/API. `admissao_publicacao` preservará uma mensagem por `reserva.admissao`, com bytes/headers originais e confirmação do primeiro ACK. Nasce com a outbox; confirmação e marcação de publicação ocorrem na mesma transação após ACK do snapshot validado. A limpeza da outbox não remove o comprovante. Backfill só usa evidência presente; legado expurgado não recebe publicação presumida. Falha após ACK e antes do commit permite reentrega idêntica, sem antecipar cancelamento. Contrato, limite de16384bytes, tratamento de legado, migração aditiva e recuperação no [ADR-013](../adrs/ADR-013-matching-execucao.md#coordenação-integrada-e-recibo-de-admissão--rf10c-2026-10-27). O FO não ganha esse registro, pois não produz `ORDEM_ADMITIDA`; a diferença entre os publicadores é intencional. Sem novo banco, serviço, dependência ou liberação de reserva.
