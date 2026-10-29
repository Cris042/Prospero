# ADR-021 — Venda a descoberto simulada com limite de exposição (RF04 e RF07)

- Data: 2026-10-28.
- Estado: **proposta implementada na V02-4, pendente de confirmação do usuário antes do merge.** As regras vêm da proposta do pedido de 2026-10-28; o usuário não estava disponível para confirmá-las durante a execução.
- Origem: RF04 e RF07 do [PRD](../prds/PRD-001-simulador-prospero.md) e a especificação de origem (venda a descoberto simulada com limite, sem aluguel). **Substitui a decisão "sem short" do [ADR-012](ADR-012-risco-reservas-saga.md).**
- Andamento: [roadmap](../roadmap.md#v02--ajustes-pós-validação).

## Contexto

O ADR-012 decidiu "sem venda a descoberto" e citou um limite de exposição vendida por conta com valor 0. A coluna `conta_risco.limite_vendido` existe desde a migração V2 do MO com `CHECK (limite_vendido = 0)`, mas nenhum código a usava (a revisão de 2026-10-28 dizia que ela não existia; a divergência real é que ela estava morta). Sem short, a posição nunca fica vendida e a virada de comprado para vendido da RF07 não acontece.

## Decisão (proposta)

1. **Partes da venda.** Uma venda de `q` usa primeiro a custódia disponível (`coberta = min(q, disponível)`). O restante (`descoberta = q − coberta`) é venda a descoberto.
2. **Limite de exposição vendida da conta**, em valor: `conta_risco.exposicao_vendida` soma `quantidade a descoberto × preço de referência do ativo` (`ativo_risco.preco_referencia`, parâmetro versionado, o mesmo usado na reserva e na liberação). Limite inicial `conta_risco.limite_vendido` = **R$ 200.000,00** por conta. Se a soma passar do limite, a ordem é rejeitada com `EXPOSICAO_VENDIDA_EXCEDIDA`. Conta com limite 0 não vende a descoberto e continua recebendo `CUSTODIA_INSUFICIENTE`.
3. **Garantia em caixa.** A parte a descoberto reserva caixa: `descoberta × teto(base)`, com o mesmo `teto` da compra Market (ADR-011/012). Sem base, `PRECO_BASE_AUSENTE`; sem caixa, `SALDO_INSUFICIENTE`.
4. **Limite de posição no lado vendido** ([ADR-020](ADR-020-limite-posicao.md)): `descoberto_reservado + descoberto_executado + descoberta` não pode passar do limite de posição da conta no ativo. Venda coberta não chega a posição negativa e não é afetada.
5. **Ordem das checagens**, todas por `UPDATE` condicional nas linhas de controle, sob o lock da conta: custódia e limite de posição (`custodia_risco`), exposição (`conta_risco`), garantia (`conta_risco`). Falha num passo desfaz os anteriores na mesma transação, e a decisão rejeitada não deixa nada preso.
6. **Ciclo de vida no MO.**
   - Execução da venda: consome primeiro a parte coberta (custódia reservada, como hoje) e depois a descoberta, que passa de `descoberto_reservado` para `descoberto_executado`; a garantia correspondente passa da reserva para `garantia_executada`, e o caixa continua reservado.
   - Encerramento com saldo (cancelamento, expiração, IOC, rejeição no ME): devolve a custódia coberta restante, tira o descoberto não executado de `descoberto_reservado` e de `exposicao_vendida` e libera a garantia restante.
   - Recompra: quando o crédito D+2 de uma compra entra na custódia (ADR-016), a quantidade primeiro cobre `descoberto_executado`. A parte coberta libera garantia proporcional (tudo quando zera) e exposição; só o excedente entra em `total`.
7. **Sem aluguel, sem taxa, sem chamada de margem**, como no PRD.
8. **Back-office.** A posição já podia ficar negativa: o BO projeta só os negócios desde a origem do log e aplica a regra de preço médio pedida (redução realiza P&L contra o médio sem alterá-lo, aumento recalcula, cruzar zero realiza a parte que fecha e abre o restante ao preço do negócio). Não muda.
9. **Razão e D+2.** As partidas não mudam. No D0 o vendedor credita `CUSTODIA`; no D+2 recebe o caixa. Para quem vende a descoberto, o saldo da conta contábil `CUSTODIA` fica negativo depois do D+2 e **representa a custódia a entregar** até a recompra, quando volta a zero. Cada transação continua balanceada por unidade. Não foi criada uma conta `CUSTODIA_A_ENTREGAR` separada porque o BO não conhece a custódia inicial do MO e, por isso, não sabe separar coberto de descoberto. Representar pelo saldo negativo evita copiar estado entre serviços. `PlanoDeContas.d2` passa a ser o único construtor do lançamento D+2.
10. **No MO, a custódia efetiva é `total − descoberto_executado`** e pode ficar negativa até o vendido a descoberto. `total`, `disponivel` e `reservado` continuam não negativos e se referem só à parte coberta.

## Alternativas

| Alternativa | Por que não |
| --- | --- |
| Permitir `custodia_risco.total` negativo | Quebra os CHECKs e as invariantes `disponível + reservado = total` usados pela RF04, RF08 e RF10; a coluna separada mantém a parte coberta intocada. |
| Limite de exposição pelo preço do negócio | O valor mudaria a cada execução e a liberação não fecharia com a reserva; o preço de referência é estável e versionado. |
| Garantia liberada na execução da recompra (D0) | A custódia da recompra só chega no D+2; liberar antes deixaria a obrigação sem garantia por dois dias. |
| Conta contábil nova no BO | Exigiria saber a custódia do MO no BO (ver item 9). |

## Consequências

- `CUSTODIA_INSUFICIENTE` deixa de aparecer para contas com limite de exposição; o cenário 2 do `risco.xlsx` e o `verificar-risco` passam a provar a venda parcialmente descoberta.
- A conta pode ter caixa reservado sem ordem viva (garantia de posição vendida executada); a carteira MO expõe `exposicaoVendida` e, por ativo, `descobertoExecutado`.
- Preparação local (RF04b) redefine a carteira inteira e zera também exposição, descoberto e garantia, como já fazia com o reservado. Ela não recusa posição vendida aberta, porque as planilhas da CI preparam a mesma conta várias vezes; a preparação é ferramenta de teste e não liquida a obrigação.

## Validação

Testes de domínio e PostgreSQL no MO (venda dentro e fora do limite, garantia reservada e liberada no cancelamento e na recompra, posição vendida no limite de posição), `PosicaoTest.viradasNosDoisSentidosAbremSaldoAoPrecoExecutado` no BO e `PlanoDeContasTest.vendaADescobertoDeixaCustodiaNegativaNoD2AteARecompraComRazaoBalanceado`. Revisar antes do merge com o usuário (valores e regras são proposta).
