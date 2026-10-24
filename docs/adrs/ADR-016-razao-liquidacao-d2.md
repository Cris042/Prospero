# ADR-016 — Razão em partidas dobradas e liquidação D+2

- Data: 2026-10-24.
- Estado: **aceita em 2026-10-24**; implementação em fatias RF08b–RF08e; RF08c (MO) em revisão.
- Origem: refinamento da RF08 após merge autorizado do PR #42 (RF07).
- Decisor: usuário, em 2026-10-24, escolhendo entre alternativas apresentadas: calendário "Seg–sex + feriados fixos", disponibilidade "Só após D+2", falhas "Sem falha simulada", legado "Exigir recriar bo-dados", plano de contas "Confirmo esse plano" após o exemplo numérico, e aceite dos defaults de histórico sem contraparte, recusa da preparação, risco PLAINTEXT e créditos ao robô. Registro no PR #43; andamento no [roadmap](../roadmap.md#rf08--razão-histórico-e-liquidação-d2).
- Relação: complementa ADR-001/002/003/004; emenda ADR-012 (nova origem de alteração da carteira MO), ADR-013 (créditos adiados passam a D+2) e ADR-015 (transação de negócio ampliada e guarda do legado). Substitui, para a liquidação local, o desenho de claim/`DESCONHECIDO` avaliado no HLD-005.

## Contexto

O [PRD RF08](../prds/PRD-001-simulador-prospero.md) exige negócios, razão em partidas dobradas, histórico e liquidação de caixa e custódia em D+2 simulado. Hoje o MO debita caixa do comprador e custódia do vendedor na execução (D0), sem creditar a contraparte (`RepositorioMatchingJpa`, ADR-013). O BO projeta posições a partir de `matching.eventos.v1`, mas `bo_negocio` guarda só identidade e assinatura, sem preço, quantidade, contas ou sessão. Kafka é PLAINTEXT e a chave HS256 é compartilhada entre serviços (ADR-012): até a RF07, um evento forjado alterava PnL exibido ou bloqueava saldo; com créditos D+2, poderia criar poder de compra.

O refinamento usou um brief comum e cinco pareceristas medium independentes (arquitetura, QA, segurança, operação e viabilidade), todos **SEGUIR COM RESSALVAS**. As divergências técnicas foram resolvidas pelos ADRs/HLD vigentes; as regras financeiras foram decididas pelo usuário. A revisão do PR #43 levou a responsabilidade do calendário só ao BO e às regras de pendência por evento no MO.

## Regras confirmadas pelo usuário (2026-10-24)

- **Calendário:** dias úteis de segunda a sexta, com feriados nacionais fixos de 2026 e 2027 em tabela do BO versionada por migração; sem API de edição. D+2 conta dois dias úteis a partir da `dataSessao` do negócio (America/Sao_Paulo, ADR-013), nunca de `ocorridoEm` em UTC; negócio em dia não útil conta a partir do próximo dia útil.
- **Disponibilidade:** débito em D0 como hoje; crédito ao vendedor (caixa) e ao comprador (custódia) entra no disponível do MO somente na liquidação. Até lá aparece como "a liquidar". Sem day trade.
- **Estados:** `PENDENTE → LIQUIDADO`. Sem falha de câmara simulada. Falha só técnica (invariante violada, contrato incoerente) em quarentena visível, sem nova tentativa automática.
- **Legado RF07:** volume BO com negócios sem fatos econômicos falha fechado com motivo `RAZAO_SEM_ORIGEM`; a correção é recriar `bo-dados` e reconstruir desde a origem do log, sem backfill nem reset automático.
- **Plano de contas** por unidade (BRL e cada ativo), com contraparte "Câmara simulada". Exemplo confirmado: CONTA-001 compra 100 ALFA3 a 20,00 de CONTA-002.

| Momento | Conta | Débito | Crédito |
| --- | --- | --- | --- |
| D0 | Comprador | Câmara 2.000,00 BRL | Caixa 2.000,00 BRL |
| D0 | Comprador | Custódia a receber 100 ALFA3 | Câmara 100 ALFA3 |
| D0 | Vendedor | Câmara 100 ALFA3 | Custódia 100 ALFA3 |
| D0 | Vendedor | Caixa a receber 2.000,00 BRL | Câmara 2.000,00 BRL |
| D+2 | Comprador | Custódia 100 ALFA3 | Custódia a receber 100 ALFA3 |
| D+2 | Vendedor | Caixa 2.000,00 BRL | Caixa a receber 2.000,00 BRL |

Débitos = créditos por transação contábil e unidade. Lançamentos são append-only; correção só por estorno vinculado à origem, sem API de correção nesta RF. Autonegociação gera os dois lados e tem efeito líquido zero após o D+2; entre D0 e D+2 a conta fica com caixa e custódia debitados e os mesmos valores a receber, coerente com a regra sem day trade.

- **Histórico sem contraparte:** consultas mostram só o lado da própria conta e o id do negócio; lançamentos contra a Câmara não revelam a outra conta.
- **Preparação local recusa pendência:** preparar carteira com liquidação pendente retorna 409 `LIQUIDACAO_PENDENTE`, antes de qualquer escrita; planilhas avançam a data antes.
- **Robô formador** recebe créditos em D+2; `verificar-robo` será ajustado.
- **Risco PLAINTEXT aceito** sem HMAC/mTLS/ACL, mitigado pela expectativa de crédito no MO.

## Alternativas

| Alternativa | Benefício | Limitação |
| --- | --- | --- |
| Creditar no MO em D0 | Simples | Contraria a regra sem day trade e o D+2 do PRD |
| Liquidação inteira no MO | Um só serviço altera a carteira | Razão e histórico são responsabilidade BO (HLD-005) |
| BO liquida e publica; MO credita sem verificar | Menos estado no MO | Evento forjado cria dinheiro |
| **BO liquida e publica; MO libera só expectativa gravada no consumo do negócio** | Evento forjado no máximo antecipa um crédito legítimo | MO ganha tabelas e consumidor novos |
| Calendário também no MO para conferir a data | MO recusaria antecipação | Duplica regra e estado; a data de movimento só existe no BO |
| Job com claim, lease e `DESCONHECIDO` | Cobre efeitos externos incertos | Excesso para efeito local atômico em instância única |

## Desenho aceito

**BO, calendário e negócio (D0).** O calendário e o cálculo da data de liquidação existem só no BO. Na mesma transação e checkpoint da RF07 entram o fato econômico do negócio (preço, quantidade, contas, ativo, `dataSessao`, data de liquidação), os lançamentos D0, as posições e a liquidação `PENDENTE` por lado. D+2 além da última data da tabela de feriados falha fechado com `CALENDARIO_INSUFICIENTE`, sem supor dia útil. Valores em `NUMERIC` com escala de centavos e quantidades inteiras; nunca `double` nem o racional da RF07. Migração V3 aditiva; `bo_lancamento` não tem estado nem aceita UPDATE/DELETE.

**BO, data de movimento.** Data persistida e monotônica, separada do relógio usado em cotação e sessão; inicia na data civil de São Paulo do primeiro uso. Avança somente por `POST` administrativo local com corpo `{dataAlvo}`:

- flag `BO_RELOGIO_HABILITADO` desligada por padrão; desligada, a rota responde 404 antes da autenticação;
- JWT com audiência BO e escopo administrativo próprio, emitido por `scripts/mercado.py` a partir da configuração local; o proxy do navegador nunca injeta esse token e descarta `Authorization` recebido nessa rota;
- 401 sem token, 403 com escopo errado; data igual é 200 sem efeito; data anterior é 409 `DATA_RETROATIVA`; mais de 10 dias úteis à frente é 422;
- UPDATE condicional sobre a data atual impede dois avanços concorrentes;
- auditoria append-only em tabela (sub, jti, de, para, instante, liquidações), não só em log.

Cada avanço liquida, em lotes limitados e retomáveis, toda pendência com data de liquidação ≤ data de movimento. O domínio recebe a data como parâmetro; ArchUnit impede `now()` direto no domínio. Não há proteção de "produção" além de flag e escopo, como nas APIs locais existentes.

**BO, liquidação (D+2).** Uma transação local muda o estado por UPDATE condicional `PENDENTE → LIQUIDADO`, grava os lançamentos D+2 e a outbox. Queda antes do commit reverte tudo; unicidade por (negócio, lado) impede liquidar duas vezes. Relay próprio do BO, cópia do padrão MO, sem biblioteca compartilhada; teste de construção do producer com a configuração real (lição RF07). O worker não entra na prontidão.

**Contrato BO → MO.** Tópico `liquidacao.eventos.v1`, 3 partições, chave = conta, retenção integral, criado pelo `kafka-init`, origem `back-office`. Evento `LIQUIDACAO_EFETIVADA` v1, um por lado, id determinístico derivado de `liquidacao:<negocioId>:<COMPRADOR|VENDEDOR>`, causação = evento do negócio, valores textuais. Derivação fixada na RF08c: `UUID.nameUUIDFromBytes` (UUID v3) dos bytes UTF-8 dessa string; campos do envelope no [HLD-003](../hlds/HLD-003-middle-office.md#crédito-de-liquidação-d2--rf08c).

**MO.** Ao consumir o negócio, grava na mesma transação a expectativa de crédito por (negócio, lado): conta, recurso, ativo e valor exato, sem data. O consumidor de `liquidacao.eventos.v1` confere origem, id derivado, chave Kafka igual à conta do evento e igualdade com a expectativa; a data de liquidação é responsabilidade do BO e não é reconferida. Resultados por evento:

- expectativa idêntica: crédito pelo mesmo UPDATE condicional por conta do ADR-012 e deduplicação por id e hash na mesma transação; só o crédito aplicado ocupa a deduplicação;
- expectativa ainda ausente: o evento fica guardado como liquidação aguardando negócio, por (id, hash), sem crédito; a transação que grava a expectativa aplica o aguardando idêntico e recusa os divergentes do mesmo id. Não trava a partição nem a prontidão; volume aguardando tem teto configurado e, acima dele, novos eventos sem expectativa são recusados com motivo;
- divergência ou contrato inválido: o evento é recusado e registrado com motivo, sem crédito e sem ocupar a deduplicação; a partição segue.

O consumidor usa o modo financeiro do `ConsumoKafka` (sem descarte) apenas para falhas técnicas transitórias; pendência e recusa são estados persistidos, não exceções. Métricas de aguardando, recusadas e idade da mais antiga, com labels finitos. Detalhes técnicos da RF08c: teto padrão 10000 (`MO_LIQUIDACAO_AGUARDANDO_TETO`) e a recusa da preparação atrás de `MO_RECUSAR_LIQUIDACAO_PENDENTE`, desligada até a RF08e, porque sem emissor toda negociação deixaria crédito pendente e quebraria as planilhas repetidas da CI.

**Consultas.** `GET /api/v1/negocios` e `GET /api/v1/razao` no BO pela conta do JWT, escopo de leitura próprio, paginação por cursor opaco com limite máximo de 100, `no-store`, 503 durante bootstrap ou quarentena. O cursor não carrega a conta como autoridade; cursor inválido ou de outra conta é 400 genérico. Negócio de que a conta não participa é 404 idêntico ao inexistente. O proxy injeta o token de leitura só por rota e método exatos, como no ADR-015.

**Logs e métricas.** Labels sem conta, valor, preço ou id de negócio; logs só com id técnico, partição/offset e motivo enumerado; recusas e aguardando do MO ficam visíveis por métrica; a prontidão reflete apenas bootstrap e quarentena técnica; nada é exposto por payload.

## Fatias

| Fatia | Escopo |
| --- | --- |
| RF08a | Este ADR, emendas, HLD-005 e roadmap; sem código |
| RF08b | BO: calendário, fatos, lançamentos D0, liquidação pendente, guarda do legado, consultas de negócios/razão, `razao.xlsx` |
| RF08c | MO: expectativa de crédito, consumidor de `liquidacao.eventos.v1`, aguardando/recusa/deduplicação, recusa da preparação; tópico no `kafka-init`, sem emissor |
| RF08d | BO: data de movimento, liquidação, outbox e relay |
| RF08e | `liquidacao.xlsx`, conciliação BO × MO por movimentos, queda e retomada, `verificar-robo`, E2E e tela |

RF08b e RF08c não dependem uma da outra. O MO tolerante entra antes do emissor: consumidor sem produtor não causa estado incoerente.

## Consequências e limites

- A conciliação é por movimentos: a preparação local redefine saldos absolutos do MO e a razão começa na origem do log.
- Kafka PLAINTEXT ainda permite forjar um negócio coerente nos dois tópicos; quem tem a configuração local emite também os tokens administrativo e de leitura (HS256 compartilhada). Riscos locais herdados dos ADR-012/015.
- Um evento de liquidação forjado com dados idênticos a uma expectativa antecipa um crédito legítimo; eventos forjados sem expectativa acumulam como aguardando, visíveis por métrica, sem crédito.
- "Falhas de liquidação" do PRD ficam restritas a falhas técnicas visíveis; falha de câmara simulada exige revisão deste ADR.
- Uma sessão por log continua; o D+2 usa a data de movimento do BO, sem abrir nova sessão no ME. Cenários com vários pregões ficam fora. Feriados além de 2027 exigem nova migração.
- Notas de corretagem, histórico frio, partições, retenção de arquivo, HMAC/mTLS e mudança do Kafka continuam fora; cada um exige tarefa e ADR próprios.

## Validação e condição de revisão

| Critério | Fatia | Evidência |
| --- | --- | --- |
| Σdébitos = Σcréditos por transação e unidade; autonegociação; sem `double` | b | Propriedade com sementes; asserção SQL em IT PostgreSQL |
| Negócio, lançamentos, posição e checkpoint atômicos; falha injetada reverte; reentrega e mesmo id com hash divergente | b | IT PostgreSQL |
| D+2: segunda → quarta, quinta → segunda, sexta → terça, feriado no intervalo e no próprio D+2, negócio em dia não útil, perto da meia-noite, `CALENDARIO_INSUFICIENTE` | b | JUnit com datas fixas |
| `RAZAO_SEM_ORIGEM` falha fechado; recriar `bo-dados` reconstrói | b | IT V2 → V3 com dados; roteiro no PR |
| Consultas: só a própria conta, 404 idêntico, cursor adulterado 400, limite, `no-store`, 503 | b | REST Assured; `razao.xlsx` (ADR-004) |
| Invariante violada no BO entra em quarentena sem nova tentativa e as consultas retornam 503 | b | IT PostgreSQL; REST Assured |
| Expectativa idêntica credita uma vez; aguardando aplica depois do negócio; divergente aguardando antes do legítimo não impede o crédito único; teto do aguardando; divergente recusado sem ocupar deduplicação; chave/origem incoerentes recusadas; partição segue | c | IT MO com eventos sintéticos |
| Preparação com pendência → 409 `LIQUIDACAO_PENDENTE` sem escrita | c | REST Assured / IT |
| Data de movimento: flag desligada 404, 401/403, retroativa 409, igual sem efeito, limite 422, concorrência, auditoria; ArchUnit sem `now()` | d | REST Assured, IT, ArchUnit |
| Proxy não injeta o token administrativo e descarta `Authorization` na rota da data de movimento | d | Teste do proxy |
| Liquidação uma vez com avanço repetido, lote maior que o limite, queda antes do commit e entre commit e relay | d | IT PostgreSQL; teste de configuração do producer |
| Conciliação BO × MO com diferença zero; planilha avança até a data devolvida pela API; duas execuções na CI | e | `liquidacao.xlsx` |
| Queda do BO durante a liquidação e MO parado no D+2 sem duplicar; robô creditado | e | CI com `kill`/`start`; `verificar-robo` |

Rever ao adotar falha de câmara, crédito antes de D+2, vários pregões, edição de calendário ou autenticação de eventos. Desenho do contexto: [HLD-005](../hlds/HLD-005-back-office.md).
