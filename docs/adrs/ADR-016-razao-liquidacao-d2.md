# ADR-016 — Razão em partidas dobradas e liquidação D+2

- Data: 2026-10-24.
- Estado: **aceita em 2026-10-24**; implementação em fatias RF08b–RF08e, nenhuma iniciada.
- Origem: refinamento da RF08 após merge autorizado do PR #42 (RF07).
- Decisor: usuário, por escolha explícita das regras abaixo entre alternativas apresentadas com exemplo numérico; andamento no [roadmap](../roadmap.md#rf08--razão-histórico-e-liquidação-d2).
- Relação: complementa ADR-001/002/003/004; emenda ADR-012 (nova origem de alteração da carteira MO), ADR-013 (créditos adiados passam a D+2) e ADR-015 (transação de negócio ampliada e guarda do legado). Substitui, para a liquidação local, o desenho de claim/`DESCONHECIDO` proposto no HLD-005.

## Contexto

O [PRD RF08](../prds/PRD-001-simulador-prospero.md) exige negócios, razão em partidas dobradas, histórico e liquidação de caixa e custódia em D+2 simulado. Hoje o MO debita caixa do comprador e custódia do vendedor na execução (D0), sem creditar a contraparte (`RepositorioMatchingJpa`, ADR-013). O BO projeta posições a partir de `matching.eventos.v1`, mas `bo_negocio` guarda só identidade e assinatura, sem preço, quantidade, contas ou sessão. Kafka é PLAINTEXT: até a RF07, um evento forjado alterava PnL exibido ou bloqueava saldo; com créditos D+2, poderia criar poder de compra.

O refinamento usou um brief comum e cinco pareceristas medium independentes (arquitetura, QA, segurança, operação e viabilidade), todos **SEGUIR COM RESSALVAS**. As divergências técnicas foram resolvidas pelos ADRs/HLD vigentes; as regras financeiras foram decididas pelo usuário.

## Regras confirmadas pelo usuário (2026-10-24)

- **Calendário:** dias úteis de segunda a sexta, com feriados nacionais fixos de 2026 e 2027 em tabela do BO versionada por migração; sem API de edição. D+2 conta dois dias úteis a partir da `dataSessao` do negócio; negócio em dia não útil conta a partir do próximo dia útil.
- **Disponibilidade:** débito em D0 como hoje; crédito ao vendedor (caixa) e ao comprador (custódia) entra no disponível do MO somente na liquidação. Até lá aparece como "a liquidar". Sem day trade.
- **Estados:** `PENDENTE → LIQUIDADO`. Sem falha de câmara simulada. Falha só técnica (invariante violada, contrato incoerente) em quarentena visível, sem nova tentativa automática.
- **Legado RF07:** volume BO com negócios sem fatos econômicos falha fechado com motivo `RAZAO_SEM_ORIGEM`; a correção é recriar `bo-dados` e reconstruir desde a origem do log, sem backfill nem reset automático.
- **Plano de contas** por unidade (BRL e cada ativo), com contraparte "Câmara simulada". Exemplo confirmado: CONTA-001 compra 100 ALFA a 20,00 de CONTA-002.

| Momento | Conta | Débito | Crédito |
| --- | --- | --- | --- |
| D0 | Comprador | Câmara 2.000,00 BRL | Caixa 2.000,00 BRL |
| D0 | Comprador | Custódia a receber 100 ALFA | Câmara 100 ALFA |
| D0 | Vendedor | Câmara 100 ALFA | Custódia 100 ALFA |
| D0 | Vendedor | Caixa a receber 2.000,00 BRL | Câmara 2.000,00 BRL |
| D+2 | Comprador | Custódia 100 ALFA | Custódia a receber 100 ALFA |
| D+2 | Vendedor | Caixa 2.000,00 BRL | Caixa a receber 2.000,00 BRL |

  Débitos = créditos por transação contábil e unidade. Lançamentos são append-only; correção só por estorno vinculado à origem, sem API de correção nesta RF. Autonegociação gera os dois lados com efeito líquido zero.
- **Histórico sem contraparte:** consultas mostram só o lado da própria conta e o id do negócio.
- **Preparação local recusa pendência:** preparar carteira com liquidação pendente retorna 409 `LIQUIDACAO_PENDENTE`; planilhas avançam a data antes.
- **Robô formador** recebe créditos em D+2; `verificar-robo` será ajustado.
- **Risco PLAINTEXT aceito** sem HMAC/mTLS/ACL, mitigado pela expectativa de crédito no MO.

## Alternativas

| Alternativa | Benefício | Limitação |
| --- | --- | --- |
| Creditar no MO em D0 | Simples | Contraria a regra sem day trade e o D+2 do PRD |
| Liquidação inteira no MO | Um só serviço altera a carteira | Razão e histórico são responsabilidade BO (HLD-005) |
| BO liquida e publica; MO credita sem verificar | Menos estado no MO | Evento forjado cria dinheiro |
| **BO liquida e publica; MO libera só expectativa gravada no consumo do negócio** | Evento forjado no máximo antecipa um crédito legítimo | MO ganha tabela e consumidor novos |
| Job com claim, lease e `DESCONHECIDO` | Cobre efeitos externos incertos | Excesso para efeito local atômico em instância única |

## Desenho aceito

**BO, negócio (D0).** Na mesma transação e checkpoint da RF07 entram o fato econômico do negócio (preço, quantidade, contas, ativo, `dataSessao`, data de liquidação calculada), os lançamentos D0, as posições e a liquidação `PENDENTE` por lado. Valores em `NUMERIC` com escala de centavos e quantidades inteiras; nunca `double` nem o racional da RF07. Migração V3 aditiva; `bo_lancamento` não tem estado nem aceita UPDATE/DELETE.

**BO, data de movimento.** Data persistida e monotônica, separada do relógio usado em cotação e sessão. Avança somente por `POST` administrativo local com escopo próprio, flag `BO_RELOGIO_HABILITADO` desligada por padrão (404 desligada), nunca injetado pelo proxy do navegador e auditado (de/para, quantidade liquidada). Cada avanço liquida, em lotes limitados, toda pendência com data de liquidação ≤ data de movimento. O domínio recebe a data como parâmetro; ArchUnit impede `now()` direto no domínio. Não há proteção de "produção" além de flag e escopo, como nas APIs locais existentes.

**BO, liquidação (D+2).** Uma transação local muda o estado por UPDATE condicional `PENDENTE → LIQUIDADO`, grava os lançamentos D+2 e a outbox. Queda antes do commit reverte tudo; unicidade por (negócio, lado) impede liquidar duas vezes. Relay próprio do BO, cópia do padrão MO, sem biblioteca compartilhada. O worker não entra na prontidão; métricas com labels finitos.

**Contrato BO → MO.** Tópico `liquidacao.eventos.v1`, 3 partições, chave = conta, retenção integral, criado pelo `kafka-init`, origem `back-office`. Evento `LIQUIDACAO_EFETIVADA` v1, um por lado, id determinístico derivado de `liquidacao:<negocioId>:<COMPRADOR|VENDEDOR>`, causação = evento do negócio, valores textuais.

**MO.** Ao consumir o negócio, grava na mesma transação a expectativa de crédito por (negócio, lado): conta, recurso, valor exato e data de liquidação. O evento do BO só credita se existir expectativa idêntica e a data de liquidação não for futura; deduplicação por id e hash na transação do crédito, que usa o mesmo UPDATE condicional por conta do ADR-012. Divergência vai para quarentena bloqueante; expectativa ainda ausente é transitória com backoff, sem crédito e sem descarte. O consumidor não usa o descarte após falhas do `ConsumoKafka` genérico.

**Consultas.** `GET /api/v1/negocios` e `GET /api/v1/razao` no BO pela conta do JWT, escopo de leitura próprio, paginação por cursor opaco com limite, `no-store`, 503 durante bootstrap ou quarentena, 404 para negócio de que a conta não participa.

## Fatias

| Fatia | Escopo |
| --- | --- |
| RF08a | Este ADR, HLD-005 e roadmap; sem código |
| RF08b | BO: fatos, lançamentos D0, liquidação pendente, guarda do legado, consultas de negócios/razão, propriedade de balanço |
| RF08c | MO: expectativa de crédito, consumidor de `liquidacao.eventos.v1`, deduplicação, quarentena, recusa da preparação; tópico no `kafka-init`, sem emissor |
| RF08d | BO: calendário, data de movimento, liquidação, outbox e relay |
| RF08e | `liquidacao.xlsx`, conciliação BO × MO por movimentos, queda e retomada, E2E e tela |

O MO tolerante entra antes do emissor: consumidor sem produtor não causa estado incoerente.

## Consequências e limites

- A conciliação é por movimentos: a preparação local redefine saldos absolutos do MO e a razão começa na origem do log.
- Kafka PLAINTEXT ainda permite forjar um negócio coerente nos dois tópicos; risco local já aceito nos ADR-012/015.
- Uma sessão por log continua; o D+2 usa a data de movimento do BO, sem abrir nova sessão no ME. Cenários com vários pregões ficam fora.
- Notas de corretagem, histórico frio, partições, retenção de arquivo, HMAC/mTLS e mudança do Kafka continuam fora; cada um exige tarefa e ADR próprios.

## Validação e condição de revisão

Aceite por fatia: Σdébitos = Σcréditos como propriedade com sementes e asserção SQL; atomicidade negócio/lançamentos/posição/checkpoint; D+2 com datas fixas (segunda → quarta, quinta → segunda, sexta → terça, feriado no intervalo, negócio em dia não útil); avanço repetido, reentrega e reinício sem duplicar; evento forjado ou divergente sem efeito; flag desligada → 404; conciliação por API com diferença zero; planilha duas vezes na CI. Rever ao adotar falha de câmara, crédito antes de D+2, vários pregões, edição de calendário ou autenticação de eventos. Desenho do contexto: [HLD-005](../hlds/HLD-005-back-office.md).
