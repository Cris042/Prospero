# ADR-020 — Limite de posição por conta e ativo (RF04)

- Data: 2026-10-28.
- Estado: **proposta implementada na V02-3**, com o valor inicial sugerido no pedido de 2026-10-28; sem aceite novo atribuído ao usuário.
- Origem: RF04 do [PRD](../prds/PRD-001-simulador-prospero.md) ("verificar o limite de posição por ativo"). Complementa o [ADR-012](ADR-012-risco-reservas-saga.md).
- Andamento: [roadmap](../roadmap.md#v02--ajustes-pós-validação).

## Contexto

O MO verificava poder de compra, custódia disponível e quantidade máxima por ordem, mas não a posição que a conta teria depois da ordem. Uma conta podia acumular qualquer quantidade de um ativo em ordens pequenas.

No MO, a custódia de uma compra só entra em `custodia_risco.total` no crédito D+2 (ADR-016); a venda sai de `total` na execução. Portanto `total` sozinho subestima a posição de quem comprou e ainda não liquidou.

## Decisão

1. **Parâmetro.** `ativo_risco.limite_posicao` (padrão por ativo, **50.000 ações**) e `custodia_risco.limite_posicao` (exceção opcional por conta e ativo, nula para usar o padrão). O robô formador de mercado (CONTA-004) recebe **200.000**, porque já nasce com 100.000 por ativo; sem exceção, nenhuma compra dele passaria.
2. **Posição aberta de compra.** `custodia_risco.compra_aberta` soma as compras reservadas e ainda não executadas e as executadas ainda não creditadas no D+2. Ela aumenta na reserva; diminui quando a reserva encerra com saldo (cancelamento, expiração, IOC/FOK, rejeição no ME) e quando o crédito D+2 entra em `total`. A execução só transfere de "reservada" para "executada sem liquidar", sem mudar o número.
3. **Regra.** Uma compra de `q` é rejeitada com `LIMITE_POSICAO_EXCEDIDO` se `total + compra_aberta + q` passar do limite (limite inclusivo). Par OCO conta a maior perna uma vez, como a reserva de caixa. O limite é avaliado **antes** do caixa; se o caixa faltar, a posição reservada é devolvida na mesma transação e o motivo é `SALDO_INSUFICIENTE`.
4. **Concorrência.** A checagem e a mutação são um único `UPDATE` condicional na linha de controle `custodia_risco` (mesmo padrão do caixa e da custódia), além do `FOR UPDATE` da conta que o actor já faz. Duas compras simultâneas não passam juntas do limite.
5. **Lado vendido.** Sem venda a descoberto, a venda nunca passa da custódia e a posição não fica negativa. O [ADR-021](ADR-021-venda-descoberto.md) estende a regra para o lado vendido.
6. **Propagação.** O motivo entra no catálogo do MO, no envelope `RESULTADO_RISCO` (campo `motivo`, sem nova versão), no enum do FO, no OpenAPI de `OrdemResponseDTO` e no rótulo da boleta.

## Alternativas

| Alternativa | Por que não |
| --- | --- |
| Fórmula líquida `total + compras pendentes − vendas pendentes + ordem` | Uma venda pendente cancelada depois deixaria a posição acima do limite. A regra por lado considera o pior caso de cada lado. |
| Calcular a posição pela soma das reservas a cada decisão | Ler e depois gravar, com duas leituras que podem atravessar o commit de uma execução. O contador na linha de controle segue o padrão do caixa. |
| Limite em valor financeiro | O pedido fixa quantidade; valor dependeria do preço de referência, que muda. |

## Consequências

- Mais uma coluna mantida em quatro pontos: reserva, encerramento com saldo, crédito D+2 e preparação local (que zera, porque exige carteira sem reserva viva e sem liquidação pendente).
- O decremento usa `GREATEST(..., 0)`. Uma divergência do contador libera o limite em vez de bloquear a liquidação: o contador apoia o risco, mas não é fato financeiro.
- A planilha `risco.xlsx` não muda: ADR-004 pede cenário novo só quando a regra é exercitável pela API com dados sintéticos estáveis, e chegar a 50.000 ações exigiria preparar a carteira com valores fora do padrão do cenário. A regra fica coberta por testes de domínio e de integração.

## Validação

`AvaliarRiscoActorTest.compraQuePassariaOLimiteDePosicaoERejeitadaSemTocarNoCaixa` (limite inclusivo, ordem limite → caixa, devolução na rejeição por saldo, motivo no envelope) e `LimitePosicaoPostgresIT` (duas compras concorrentes com espaço para uma só; padrão 50.000 e exceção do robô). Os ITs de matching, cancelamento e liquidação do MO cobrem os decrementos.
