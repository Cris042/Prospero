# ADR-011 — Validação de lote, tick e túnel de preço no front office (RF03)

- Data: 2026-10-17.
- Estado: **aceita e implementada** na RF03.
- Origem: refinamento da [RF03](../roadmap.md#rf03--validação-da-ordem) com cinco pareceres medium (arquitetura, QA, segurança, operação, viabilidade), todos **SEGUIR COM RESSALVAS**.
- Decisor: usuário (base do túnel, Market, Stop Limit, parâmetros do OMEGA3); detalhes técnicos consolidados dos pareceres.

## Decisões

| Tema | Decisão |
| --- | --- |
| Onde | FO, síncrono no `POST`, 422 com código estável e sem persistir (ADR-010). O FO é dono do catálogo (`ativo`: tick, lote, referência). O MO poderá revalidar na RF04 como defesa em profundidade; o catálogo de códigos é o contrato reaproveitado como motivo de rejeição assíncrona. |
| Ordem das regras | Estrutura → matriz/campos por tipo e coerência alvo/disparo do OCO → lote → tick → coerência Stop Limit → **consulta da chave de idempotência** → túnel (só envios novos). A repetição de um envio aceito devolve sempre a resposta original, mesmo que a base mude. Recusa não consome chave nem cota diária. |
| Lote e tick | Quantidade múltipla do lote (`QUANTIDADE_FORA_DO_LOTE`); todo preço informado (limite, disparo, alvo e limite do Stop Loss) múltiplo do tick (`PRECO_FORA_DO_TICK`). **OMEGA3 passa a lote 100 e tick 0,05** (migração V4), para as regras aparecerem pela API; demais ativos seguem tick 0,01 e lote 1. |
| Base do túnel | Último negócio real da **sessão corrente**; sem ele (antes do primeiro negócio ou cotação de sessão anterior), o `preco_referencia` do ativo. Porta `FonteUltimoPreco`; até a RF05/RF06 a implementação não tem negócios e a base é sempre a referência. O snapshot sintético da RF01 **nunca** é base (ADR-009): `ordens` não depende de `mercado` (ArchUnit). |
| Túnel | ±10% (`prospero.ordens.tunel-percentual`, validado em (0, 50] no início). Limites inclusivos arredondados ao tick para dentro: mínimo `⌈base×0,9/tick⌉×tick`, máximo `⌊base×1,1/tick⌋×tick`, em `BigDecimal`. Recusa `PRECO_FORA_DO_TUNEL` com `detalhes` públicos (`minimo`, `maximo`, `base`, `origemBase`). |
| Aplicação | Limit: limite. Stop: disparo. Stop Limit: disparo e limite. OCO: alvo, disparo e limite do Stop Loss. **Market**: sem checagem de preço na entrada; a execução fica limitada ao túnel no matching (RF05) e o saldo é cancelado (IOC/FOK). |
| Stop Limit | Em toda Stop Limit (simples ou perna do OCO): venda com limite ≤ disparo; compra com limite ≥ disparo; igualdade aceita (`STOP_LIMIT_INCOERENTE`). |
| Auditoria | A ordem persiste `preco_base_tunel` e `origem_base` (`ULTIMO`/`REFERENCIA`); null para Market e ordens anteriores à RF03. |
| Estado | Sem `REJEITADA` persistida; o enum cresce na RF04. |

## Consequências

- Problem Details ganharam `detalhes` (mapa público opcional); a boleta mostra a faixa aceita em pt-BR.
- O túnel é avaliado só na entrada; ordens DIA no livro não são revalidadas quando a base muda (prática de mercado), e a corrida até o matching é protegida pela faixa de execução da RF05.
- `preco_referencia` é estático até que RF06/RF08 definam a atualização por sessão (fechamento).
- Exceção de transição: lote e tick são verificados antes da chave; uma ordem OMEGA3 aceita antes da V4 e repetida em até 24 h recebe a recusa de lote/tick em vez do 202 repetido.
- A fonte de último negócio (RF06) deve entregar preço com escala ≤ 2 e múltiplo do tick, pois `preco_base_tunel` é `NUMERIC(12,2)`; a corrida entre consulta da chave e registro só importa quando a base puder mudar.
- O percentual é validado no início da aplicação (bean `@Startup`): valor fora de (0, 50] impede a subida.
- Testes: helper puro (fronteiras, arredondamento, bases mínimas e máximas, tick 0,05/lote 100), REST com fonte controlada (ramo `ULTIMO`, mudança de base com repetição) e workbook `validacao.xlsx` contra o PostgreSQL real.

## Revisão

Revisar na RF04 (motivos de rejeição assíncrona no MO), RF05 (faixa de execução da Market) e RF06 (fonte real do último negócio e atualização da referência).
