# ADR-012 — Risco, reservas e SAGA FO ↔ MO (RF04)

- Data: 2026-10-18.
- Estado: **aceita e implementada** — RF04a (contrato, reserva e SAGA, PR #21) e RF04b (carteira e preparação local no MO, `risco.xlsx`, tela, prova SQL e recuperação).
- Origem: refinamento da [RF04](../roadmap.md#rf04--risco-reservas-e-saga) com cinco pareceres medium (arquitetura, QA, segurança, operação, viabilidade), todos **SEGUIR COM RESSALVAS**.
- Decisor: usuário (carteiras iniciais e preparação local, sem short, preço de reserva sem limite, OCO e quantidade máxima); detalhes técnicos consolidados dos pareceres.

## Contexto

A RF02/RF03 registram a ordem como `RECEBIDA` sem reserva. O PRD exige poder de compra, posição, exposição vendida e quantidade máxima verificados, com recursos reservados antes do matching (RNF05) e reentregas sem efeito duplicado (RNF06). ADR-002 fixa SAGA orquestrada pelo FO com outbox; HLD-003 mostra que só uma atualização condicional na linha de controle protege a invariante agregada contra write skew.

## Decisões de negócio (usuário, 2026-10-18)

| Tema | Decisão |
| --- | --- |
| Carteiras | O MO semeia por migração CONTA-001..003 com R$ 1.000.000,00 e 1.000 ações de cada ativo. RF04b acrescenta preparação local (escopo próprio, desligada fora do ambiente local) que redefine CONTA-002/003 por cenário. |
| Short | **Sem venda a descoberto** na RF04: venda exige custódia disponível (`CUSTODIA_INSUFICIENTE`). Limite de exposição vendida existe como parâmetro por conta com valor 0; ativá-lo exige nova decisão. |
| Compra sem limite | Market: `quantidade × teto(base)`; Stop: `quantidade × teto(gatilho)`; `teto(p) = ⌊p × (1 + túnel) / tick⌋ × tick` (ADR-011). Limit e Stop Limit: `quantidade × limite`. Invariante para a RF05: o ME nunca executa compra acima do preço unitário reservado. |
| OCO | Uma decisão e uma reserva para o par: compra reserva o maior custo entre as pernas; venda reserva a quantidade de custódia uma vez. As duas pernas mudam de estado juntas. |
| Quantidade máxima | Parâmetro por ativo no MO, 100.000 por ordem (`QUANTIDADE_MAXIMA_EXCEDIDA`), abaixo do teto estrutural de 1.000.000 do FO. |

## Decisões técnicas

| Tema | Decisão |
| --- | --- |
| Tópicos | `risco.comandos.v1` (FO → MO) e `risco.resultados.v1` (MO → FO), chave = conta, 3 partições, retenção 7 dias e 64 MiB por partição. Criados por serviço único `kafka-init` (`--if-not-exists`); auto-create continua desligado. O tópico do ME (RF05) será separado, com chave = ativo. |
| Envelope | JSON até 16 KiB com `id` (UUID do evento = linha da outbox), `tipo` (`AVALIAR_RISCO`/`RESULTADO_RISCO`), `versao` 1, `origem`, `correlacao` (= `grupoEnvio`: ordem simples ou grupo OCO), `ocorridoEm`, `conta`, `traceparent` W3C (também em header) e `dados`. Valores monetários como texto decimal. Leitura estrita: campo desconhecido, chave duplicada, tipo/origem divergente do tópico ou versão não suportada não produz efeito. |
| Outbox | FO grava ordem, chave de idempotência e comando na **mesma transação**; MO grava decisão, reserva, linha de controle e resultado na mesma transação. Relay por polling (lote 50 limitado a 20 s, `ORDER BY criado_em, id`, `FOR UPDATE SKIP LOCKED`), produtor `acks=all` idempotente, `publicado_em` marcado só após ack; uma instância por serviço preserva a ordem por conta (melhor esforço). Entrega pelo menos uma vez; sem promessa de exatamente uma vez. Cliente Kafka puro (`quarkus-kafka-client` já presente), sem nova dependência. |
| Consumo | Offset confirmado só após o commit do efeito. Falha de processamento reposiciona no registro e repete com espera crescente (0,5 s a 30 s); após 10 falhas seguidas no mesmo offset o registro é descartado como inválido, para um defeito determinístico não travar a partição (se nem o descarte puder ser gravado, segue repetindo). Mensagem inválida grava diagnóstico limitado (sem payload, expurgo após 7 dias) e avança. Métricas `prospero_consumo_ativo`, `prospero_consumo_falhas_total` e `prospero_consumo_descartadas_total` por tópico. Sem DLQ nesta RF. |
| Deduplicação | MO: a **intenção** (`correlacao`) é a chave primária da decisão; reentrega, com o mesmo ou outro `id`, não cria reserva nem outro resultado. FO: transição condicional `RECEBIDA → RESERVADA/REJEITADA` filtrada por conta e grupo; resultado repetido, de outra conta ou de ordem finalizada é ignorado. |
| Concorrência | `UPDATE … SET disponivel = disponivel - v, reservado = reservado + v WHERE conta = c AND disponivel >= v` (caixa) e o equivalente por `(conta, ticker)` (custódia), com `CHECK disponivel + reservado = total` e não negativos. Zero linhas = rejeição. A partição por conta já serializa o consumo, mas a proteção não depende disso. |
| Estados FO | `RECEBIDA` (aguardando risco) → `RESERVADA` ou `REJEITADA` com `motivoRejeicao`. Sem estado intermediário: o FO não sabe quando o relay publicou. `RESERVADA` não implica execução; o ME ainda não consome. Ordem sem resposta permanece `RECEBIDA`; timeout não libera nem rejeita. |
| Motivos | `SALDO_INSUFICIENTE`, `CUSTODIA_INSUFICIENTE`, `QUANTIDADE_MAXIMA_EXCEDIDA`, `QUANTIDADE_FORA_DO_LOTE`, `ATIVO_DESCONHECIDO`, `CONTA_DESCONHECIDA`, `PRECO_BASE_AUSENTE`. Erro ou dado ausente nunca aprova. |
| Fatos do MO | Parâmetros dos ativos (lote, tick, referência, túnel, quantidade máxima, versão) e contas replicados por migração própria do MO, sem FK nem banco compartilhado. A base do túnel (último/referência) vem do FO no comando, pois só o FO tem a projeção de mercado; o MO recalcula o valor da reserva e nunca aceita valor a reservar do FO. |
| Segurança do canal | Kafka PLAINTEXT sem HMAC/mTLS é **risco aceito no ambiente local** (HLD-001); mitigação: tópico → origem permitida, transição só a partir de `RECEBIDA` da mesma conta, decisões recalculadas pelo MO. HMAC/mTLS exigem ADR próprio. |
| Aritmética | `BigDecimal` com escala 2 e `NUMERIC(18,2)`; quantidade × preço máximo (10⁶ × 1,1·10⁶) cabe. |

## RF04b — consulta, preparação e provas

| Tema | Decisão |
| --- | --- |
| Autenticação no MO | JWT HS256 com a mesma chave local do FO e **audiência própria** `prospero-middle-office`: token do FO não vale no MO. Escopos `risco:leitura` (com `sub` = conta) e `risco:preparacao` (sem conta). |
| Carteira | `GET /api/v1/risco/carteira`: caixa e custódia (total, disponível, reservado) só da conta do token; conta inexistente é 403 `CONTA_DESCONHECIDA`. |
| Preparação | `PUT /api/v1/preparacoes-risco/contas/{conta}` só para CONTA-002/003 (`CONTA_NAO_PREPARAVEL` para as demais), habilitada por `MO_PREPARACAO_HABILITADA` (padrão desligado; ligada só no Compose local). Bloqueia a linha de controle, redefine saldo e custódia (ativos ausentes = 0) e apaga as reservas da conta; decisões permanecem para a deduplicação. Ordens dessas contas já `RESERVADA` ficam sem recurso correspondente — aceitável só em teste. |
| Tela | A boleta acompanha o envio por polling (500 ms, até 10 s) e mostra `Reservada` ou `Rejeitada (motivo)`; sem resposta, continua `Recebida`. |
| Provas | `risco.xlsx` (caixa, custódia, teto Market, OCO, quantidade máxima, isolamento), `verificar-risco` repetível (prepara CONTA-002 e confere a carteira), `verificar-concorrencia` (duas conexões PostgreSQL: ler-e-gravar concede 140 sobre 100; UPDATE condicional concede só 70, com a segunda sessão esperando o lock e reavaliando para `UPDATE 0`) e `verificar-recuperacao` (MO parado mantém `RECEBIDA` e decide uma vez; comando reentregue e mensagem inválida no tópico sem efeito). |

## Riscos residuais aceitos (ambiente local)

- **Comando forjado:** sem ACL, qualquer contêiner da rede `eventos` pode publicar `AVALIAR_RISCO` com intenção inventada e reservar saldo sem ordem correspondente. Como ainda não há liberação (RF05/RF10), o saldo fica bloqueado até recriar os volumes ou usar a preparação local da RF04b. O recálculo pelo MO impede só valor inflado.
- **Resultado forjado:** um produtor que leia `risco.comandos.v1` pode publicar `RESERVADA` antes do MO; o FO aplica o primeiro resultado e descarta o verdadeiro. A RF05 não pode confiar no estado do FO: o ME/MO confirmam a reserva antes de executar.
- **Decisões de contas inexistentes** e diagnósticos ficam no banco do MO (amplificação 1:1 por mensagem válida no formato).
- Mitigação fora do local: SASL + ACL por principal (FO escreve só em comandos, MO só em resultados) ou HMAC por envelope, em ADR próprio.

## Alternativas

- **MO aprovando tudo antes do risco** (fatiar por transporte): rejeitada — criaria `RESERVADA` sem reserva, contrário à RNF05.
- **SmallRye Reactive Messaging**: nova dependência e commit de offset menos explícito; cliente puro escolhido.
- **Tópico DLQ**: mais infraestrutura sem operação de reprocessamento; registro local basta.
- **Reserva por perna no OCO**: dobraria o bloqueio para ordens mutuamente exclusivas.
- **Short com limite**: adiado por decisão do usuário.

## Consequências

- `prospero_risco_decisoes_total{resultado,motivo}` no MO e `prospero_outbox_publicados_total`/`prospero_outbox_pendentes` nos dois serviços, sem conta ou ID em labels.
- A RF05 deve consumir somente ordens `RESERVADA`, respeitar o preço unitário reservado e consumir/liberar a reserva; RF10 libera saldo cancelado.
- A preparação de carteiras da RF04b apaga reservas das contas de teste; ordens já `RESERVADA` dessas contas ficam sem recurso correspondente, aceitável só no ambiente local.
- Testes: helpers puros de cálculo, Actor do MO com repositório em memória, aplicação de resultado no FO, envelope adulterado; contra o ambiente real, `scripts/mercado.py verificar-risco` (duas compras concorrentes que somadas excedem o saldo → uma `RESERVADA` e uma `REJEITADA`; venda sem custódia; quantidade máxima).

- Ordens registradas antes da V5 do FO não têm comando na outbox e permanecem `RECEBIDA`; a V5 não as republica (não há base de Market gravada). Tratar na RF04b/RF05 se forem relevantes.
- `ConsumoKafka`, `RelayOutbox`, `ConfiguracaoKafka` e `Rastreio` são cópias espelhadas em FO e MO (projetos Maven independentes, sem artefato compartilhado — ADR-001); correções valem para os dois.

## Revisão

Revisar na RF04b (preparação, prova SQL com duas conexões, recuperação), RF05 (consumo da reserva e preço máximo), RF08 (efeitos de liquidação no saldo) e ao adotar HMAC/mTLS ou short.
