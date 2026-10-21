# ADR-013 — Matching, admissão pelo MO e ciclo de execução (RF05)

- Data: 2026-10-18.
- Estado: **aceita**; implementação em fatias RF05a–RF05d (roadmap).
- Origem: refinamento da [RF05](../roadmap.md#rf05--matching-e-ciclo-de-execução) com cinco pareceres medium (arquitetura, QA, segurança, operação, viabilidade), todos **SEGUIR COM RESSALVAS**.
- Decisor: usuário (admissão pelo MO, robô formador de mercado na RF05, autonegociação permitida, efeitos de caixa/custódia só na RF08); detalhes técnicos consolidados dos pareceres.
- Relação: **substitui parcialmente o ADR-002** quanto à orquestração — o FO continua dono do estado visível da ordem, mas a admissão no matching é publicada pelo MO; complementa ADR-010, ADR-011 e ADR-012.

## Decisões do usuário (2026-10-18)

| Tema | Decisão |
| --- | --- |
| Admissão | O **MO** publica `ORDEM_ADMITIDA` em `matching.comandos.v1` na mesma transação da reserva (outbox). O ME aceita comandos só de `origem = middle-office`. O FO nunca coloca ordem no livro; isso retira o estado do FO do caminho legítimo de admissão, mas a origem textual não autentica o produtor Kafka. |
| Liquidez | Robô formador de mercado **já na RF05** (fatia RF05d): conta própria, ofertas pela API do FO com reserva no MO como qualquer investidor, cadência e preços determinísticos por semente. Nada de livro semeado nem atalho que pule o risco (RNF05). |
| Autonegociação | Permitida e documentada (a demo da CONTA-001 pode negociar consigo mesma); testes usam contas distintas. |
| Caixa e custódia | A execução **consome só o recurso reservado** (compra: caixa ao preço executado, liberando a diferença e sobras; venda: custódia). Ações do comprador e caixa do vendedor só entram na RF08 (D+2) — sem day trade até lá. |

## Decisões técnicas

| Tema | Decisão |
| --- | --- |
| Livro | Por ativo: compras por preço decrescente e vendas crescente, FIFO por nível (sequência de chegada ao ME, nunca horário), índice por ID. Negócio ao **preço da oferta passiva**. Livro nunca cruzado após cada comando. |
| Faixa da Market | Base = último negócio do próprio ME na sessão, senão referência. Compra executa a `≤ min(⌊base×1,1/tick⌋×tick, preço máximo reservado)`; venda a `≥ ⌈base×0,9/tick⌉×tick`. Market nunca repousa: sobra cancelada (`FORA_DA_FAIXA` se o túnel interrompeu, `LIMITADA_PELA_RESERVA` se o preço reservado foi o corte mais restritivo, senão `SEM_LIQUIDEZ`). |
| Limit | Compra com limite acima do preço máximo reservado é rejeitada (`PRECO_ACIMA_DA_RESERVA`). DIA repousa o saldo; IOC cancela a sobra (`SALDO_CANCELADO_IOC`); FOK verifica antes a quantidade inteira executável sem tocar o livro (`FOK_SEM_LIQUIDEZ`). |
| Stops (RF05c) | Gatilho pelo último negócio do ME, igualdade aciona (compra `último ≥ gatilho`, venda `último ≤ gatilho`); gatilho já satisfeito na entrada aciona imediatamente. Stop acionado vira Market IOC com faixa; Stop Limit vira Limit DIA sem prioridade original. Cascata no mesmo comando, em ordem de acionamento e sequência. |
| OCO (RF05c) | Parcial numa perna reduz a outra na mesma quantidade; execução total cancela a outra; acionamento do stop cancela o Take Profit. A reserva única do par é consumida uma vez. |
| Sessão (RF05c) | Validade DIA expira por comando `ENCERRAR_SESSAO` gravado no log (reproduzível), liberando reservas. Sem relógio do ME. |
| Determinismo | Núcleo puro sem relógio, aleatoriedade nem I/O; tempo vem do comando. ID do negócio = `TICKER-sessão-n`, sequência por ativo e sessão. |
| Limites | Por comando: até 256 negócios (sobra cancelada `LIMITE_NEGOCIOS`); até 1000 ordens ativas por conta e ativo (`LIMITE_ORDENS_ATIVAS`); stops pendentes 500 por ativo e cascata 32 (RF05c). Determinísticos e iguais no replay. |
| Mensageria (RF05b) | `matching.comandos.v1` (MO → ME) e `matching.eventos.v1` (ME → MO, FO), chave = ativo, criados pelo `kafka-init`. Eventos: `ORDEM_ACEITA`, `OFERTA_NO_LIVRO`, `NEGOCIO` (dois lados), `ORDEM_ENCERRADA{motivo}`, `ORDEM_REJEITADA{motivo}`; catálogo já previsto para RF06 (profundidade) e RF10 (`CANCELAR`). |
| Recuperação (RF05b) | Livro só em memória; na partida o ME refaz a partição desde o início da sessão sem publicar até o último offset publicado (guardado no PostgreSQL do ME) e depois volta a publicar. Pelo menos uma vez com IDs determinísticos e deduplicação no MO/FO, sem transações Kafka nem snapshot (snapshot só se a medição do reinício exigir). Falha no ME para a partição (fail-stop), sem descartar registro. |
| Laboratório (RF05b) | Uma sessão por log, três partições fixas, uma instância com posse advisory PostgreSQL em conexão mantida e atribuição manual. Até 10.000 registros por partição; origem zero e identidade dos tópicos preservadas, retenção integral sem expurgo automático. Checkpoint só após todos os acks do comando; republicação parcial conserva IDs. Falha/lacuna bloqueia prontidão, sem liberar reservas. Não implementa rebalanceamento automático nem virada diária. Contratos e recuperação no HLD-004. |
| Reserva no MO (RF05b) | Reserva por intenção ganha saldo restante, preço unitário máximo e estados `ATIVA`/`PARCIAL`/`ENCERRADA`; consumo por UPDATE condicional (restante e preço); execução fora da reserva vai para quarentena com métrica, sem efeito. Preparação local recusa (409) conta com ordens vivas. |
| Estados no FO (RF05b) | `RESERVADA` → `ABERTA`, `PARCIALMENTE_EXECUTADA`, `EXECUTADA`, `CANCELADA{motivo}` (e `REJEITADA` pelo ME). |

## Alternativas

- **FO admite após `RESERVADA`**: ADR-002 puro, mas o ME teria de confirmar a reserva com o MO (latência e passo extra); rejeitada.
- **Transações Kafka (EOS) + snapshots**: proposta pela arquitetura; adiada — o padrão pelo menos uma vez com IDs determinísticos já está validado na RF04 e evita `read_committed`/`transactional.id`.
- **Livro semeado** como liquidez: viola RNF05; rejeitado em favor do robô com reserva.
- **Biblioteca de propriedades (jqwik)**: nova dependência; a RF05a usa gerador próprio com semente fixa em JUnit.

## Consequências

- O relay por polling de 200 ms soma latência por salto; a RF05 só **mede** RNF04 (sem prometer p99 < 50 ms).
- Ordens `RESERVADA` anteriores à RF05b permanecem preservadas sem admissão automática: os dados legados não incluem validade, sessão nem todos os IDs necessários. Não fabricar metadados nem consultar banco do FO para republicá-las. Reconciliação explícita futura precisa de contrato próprio.
- Riscos residuais do canal PLAINTEXT crescem: um `ORDEM_ENCERRADA` forjado poderia liberar reserva de ordem viva; eventos terminais valem só de `origem = matching-engine` e o risco fica registrado até ACL/HMAC.

## Revisão

Revisar ao fim de cada fatia, na RF06 (profundidade e último preço), RF08 (créditos D+2) e RF10 (cancelamento).
