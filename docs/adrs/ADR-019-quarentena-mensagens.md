# ADR-019 — Quarentena de mensagens Kafka no lugar do descarte

- Data: 2026-10-28.
- Estado: **proposta técnica implementada na V02-2**, sem aceite novo atribuído ao usuário.
- Origem: revisão pós-V01 (item 2 do pedido de 2026-10-28) e RNF05 do [PRD](../prds/PRD-001-simulador-prospero.md). Emenda o laço de consumo do [ADR-012](ADR-012-risco-reservas-saga.md).
- Andamento: [roadmap](../roadmap.md#v02--ajustes-pós-validação).

## Contexto

O `ConsumoKafka` do FO e do MO descartava a mensagem depois de dez falhas seguidas no mesmo offset e guardava só a posição e o motivo em `evento_rejeitado`, sem o payload. Uma queda do banco por um minuto, ou um deploy com contrato desalinhado, deixava ordens em `RECEBIDA` sem caminho de recuperação, o que contradiz o RNF05 ("nenhuma reserva perdida após falha"). Os tópicos `risco.*` também tinham `retention.bytes=67108864`, e com isso um serviço fora por tempo suficiente podia perder mensagens ainda não consumidas.

## Decisão

1. **Classificação da falha.** `ClassificadorFalha` percorre a cadeia de causas. São transitórias: `SQLTransientException`, `SQLRecoverableException`, SQLState `08*`, `53*`, `57P*`, `40001` e `40P01`, pool Agroal esgotado, `ConnectException`/`SocketTimeoutException`, `TimeoutException`, `RetriableException` do Kafka e timeouts de lock JPA. Falha transitória não conta para o limite de tentativas: o consumo espera com backoff exponencial (500 ms até 30 s), sem avançar o offset, e segue depois que o banco volta.
2. **Erro permanente vai para a quarentena.** `MensagemInvalidaException` (envelope, contrato, chave) vai direto; outra falha não transitória vai depois de dez tentativas seguidas. A tabela `mensagem_quarentena` (renomeada de `evento_rejeitado`) guarda tópico, partição, offset, chave, `traceparent`, payload de até 1 MiB, motivo, tentativas e horário, com unicidade por posição.
3. **Ordem em relação ao offset.** O offset fica no Kafka, e não existe transação comum entre o PostgreSQL e o commit do consumidor. A garantia equivalente: a linha é gravada e confirmada **antes** do `commitSync` do offset. Se a gravação falhar, o offset não avança. Se o commit falhar depois da gravação, a reentrega cai na mesma linha (`ON CONFLICT` na posição), sem duplicar.
4. **Consumidores financeiros** (matching e liquidação no MO, matching e contabilização no FO) continuam bloqueando a partição diante de erro permanente, como antes. A linha de quarentena agora traz o payload para diagnóstico; a recuperação continua sendo corrigir e reiniciar, e depois descartar a linha.
5. **Reprocessamento.** `POST /api/v1/quarentena/{id}/reprocessamento` no FO e no MO, com escopo `quarentena:reprocessar` e audiência do próprio serviço, reconstrói o registro e chama o mesmo processador do consumidor, sem passar pelo Kafka. A deduplicação de cada caso de uso torna a repetição inócua, e a linha fecha com atualização condicional (`resolvido_em IS NULL`). Se falhar de novo, responde 409, conta a tentativa e mantém a linha pendente. `POST /{id}/descarte` registra o descarte explícito. `GET /api/v1/quarentena` lista as pendentes **sem payload**. Hoje há reprocessador para `risco.resultados.v1` (FO) e `risco.comandos.v1` (MO); os demais tópicos respondem 422 `TOPICO_SEM_REPROCESSADOR`. Token: `python3 scripts/mercado.py token --escopo quarentena-fo|quarentena-mo`.
6. **Acesso e retenção.** A tabela existe só no banco do serviço. O payload pode conter conta e ordem, e nenhuma rota o devolve. O relay apaga linhas com mais de **30 dias**, resolvidas ou não. Linhas anteriores à V02 ficam sem payload e só aceitam descarte.
7. **Métricas e alertas.** `prospero_consumo_quarentena_total{topico,motivo}`, `prospero_consumo_transitorias_total{topico}`, gauge `prospero_quarentena_pendentes{topico,motivo}` e `prospero_quarentena_idade_segundos`, medidos pelo relay a cada 5 s. No FO, `prospero_ordens_recebidas` e `prospero_ordens_recebidas_idade_segundos`. Alertas `ProsperoQuarentenaPendente` (1 min) e `ProsperoOrdemParadaRecebida` (mais antiga acima de 120 s por 1 min), com testes no `alertas.test.yml`.
8. **Retenção de `risco.*`** passa a ser só por tempo (7 dias, `retention.bytes=-1`). O `kafka-init` corrige volumes existentes com `kafka-configs --alter`.

## Alternativas

| Alternativa | Por que não |
| --- | --- |
| Tópico DLQ no Kafka | Mais um tópico, produtor e permissões; reprocessar exigiria republicar e alteraria a ordem do log. A tabela local reaplica pelo mesmo caso de uso. |
| Pausar só a partição com falha transitória | Com um banco por serviço, a queda atinge todas as partições do consumidor. A espera global mantém o laço atual, já testado. |
| Guardar o offset no PostgreSQL para quarentena e avanço na mesma transação | Mudaria o modelo de commit dos quatro consumidores (ADR-012); a idempotência por posição dá o mesmo resultado observável. |
| Reprocessar por script em `scripts/` | Precisaria acessar o banco ou republicar no Kafka; a API com escopo reaproveita autenticação, problema RFC 9457 e testes. |

## Consequências

- Mensagem nenhuma sai do fluxo sem deixar payload recuperável. O custo é guardar dados de conta por até 30 dias no banco do serviço.
- Reaplicar fora de ordem pode falhar de novo por regra de estado; nesse caso a linha continua pendente e o alerta segue ativo.
- Um defeito determinístico que não seja `MensagemInvalidaException` ainda segura a partição por dez tentativas, cerca de 2 min com o backoff, antes de ir para a quarentena.
- A classificação por lista pode errar em exceções novas. O erro conservador (tratar como permanente) leva à quarentena e é recuperável pelo reprocessamento.

## Validação

`ClassificadorFalhaTest` (inclusive o driver PostgreSQL real recusando conexão), `QuarentenaConsumoTest` (banco fora por 20 ciclos sem quarentena e retomada; permanente com payload, chave e `traceparent`; quarentena indisponível sem avanço), `ReprocessarQuarentenaActorTest`, `QuarentenaResourceTest` (401/403, listagem sem payload, idempotência), `QuarentenaPostgresIT` e `QuarentenaMigracaoPostgresIT` no FO e no MO, mais os testes promtool. Revisar se surgir a necessidade de reprocessar tópicos financeiros sem reiniciar o consumidor.
