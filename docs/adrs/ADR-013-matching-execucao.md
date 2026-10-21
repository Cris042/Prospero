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
| Sessão (RF05c) | Validade DIA expira por comando explícito por ativo/data, sem relógio do ME. RF05c.1a implementa só o núcleo puro: expira saldo em ordem de chegada, fechamento repetido inócuo, impede admissão tardia e troca de data sem fechar; deduplicação histórica preservada. Comando no log e liberação real de reservas continuam na integração futura. |
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

## Detalhamento da RF05c.1a (2026-10-19)

Refinamento independente de arquitetura, QA, segurança, operação e viabilidade consolidado no roadmap: núcleo de encerramento antes de integrar controle de sessão, stops e OCO. O domínio devolve terminais `EXPIRADA_DIA` somente para ofertas ainda vivas, com executada acumulada e cancelada igual ao saldo. Fechamento por ativo/data estabelece fronteira inclusive com livro vazio; reentrega do fechamento é inócua. Não expurgar IDs: o mesmo ID de ordem não pode reaparecer noutra sessão/ativo. Nova data no núcleo só após fechar a anterior, reiniciando último preço e sequências; isso não remove a restrição operacional de uma sessão por log da RF05b. Contrato e limites no HLD-004. Sem mudança de requisito, dependência ou garantia financeira integrada.

As duas regras de stops que estavam pendentes foram decididas explicitamente pelo usuário em 2026-10-20, após exemplos das alternativas, conforme seção seguinte. Integração OCO depende de reserva de grupo e progresso distinto de execução/redução/cancelamento por perna. Encerramento integrado depende de autoridade/correlação do comando por ativo e recuperação comprovada; não adotar endpoint administrativo sem contrato nem apagar logs/checkpoints para simular abertura diária.

## Decisões de stops — RF05c.1b (2026-10-20)

O usuário aceitou **cancelar os stops excedentes à 32ª ativação do comando** e **executar os stops após o agressor terminar**. As alternativas de reter excesso para outro comando e intercalar execuções entre negócios do agressor foram descartadas: a primeira exigiria contrato de retomada da fila; a segunda disputaria a liquidez prevista pela FOK. Não desfazer negócios efetivados.

Capturar o gatilho em cada negócio e remover o stop da pendência de forma irrevogável, mesmo se o preço inverter antes de executar a fila. Sem último negócio da sessão, a referência não dispara stops. Acionamentos simultâneos seguem chegada original; a fila segue ocorrência de acionamento e chegada. Drenar após finalizar o agressor (terminal ou saldo no livro), sem recursão/interleaving, e depois repetir para os stops convertidos. São até32 seleções acumuladas por comando raiz, sem renovar vagas ao esvaziar a fila; excedentes recebem `LIMITE_CASCATA`, executada0 e cancelada igual à quantidade. Stop selecionado conserva identidade, intenção, conta, quantidade e reserva; conversão ganha sequência nova e não passa novamente pela deduplicação da admissão.

O teto256 de negócios é compartilhado por agressor e toda cascata. Ativações selecionadas que encontram esse orçamento esgotado recebem `LIMITE_NEGOCIOS`, sem tentar execução nem deixar saldo no livro. Ao gastar o último negócio permitido, saldo remanescente recebe esse motivo mesmo sem contraparte restante; uma execução integral continua `EXECUTADA`. O limite32 tem precedência para candidatos não selecionados. Stops ainda não acionados permanecem pendentes e não são cancelados por esses limites. Até500 pendentes por ativo (`LIMITE_STOPS_PENDENTES`); stop imediatamente acionável não ocupa armazenamento de pendentes. Até1000 ativas por conta/ativo inclui ofertas e stops, sem duplicação na conversão. Fechamento expira conjuntamente pendentes pela chegada e ofertas pela prioridade corrente, preservando deduplicação histórica.

A fatia acrescenta somente domínio puro e testes: admissão de stops apenas DIA e coerência Stop Limit já seguem ADRs010/011. Eventos internos `StopPendente` e `StopAcionado` distinguem espera de conversão; não são novos envelopes Kafka. Adaptador mantém allowlist explícita Market/Limit e uma sessão por log. OCO, liberação financeira real dos stops e controle integrado de sessão continuam futuros; não requer migração, API ou dependência nova.

## Revisão

Revisar ao fim de cada fatia, na RF06 (profundidade e último preço), RF08 (créditos D+2) e RF10 (cancelamento).

## Contrato da integração de stops — RF05c.2 (2026-10-20)

Recorte técnico da decisão aceita: novas ordens simples Stop/Stop Limit DIA atravessam MO → ME → FO/MO; OCO, fechamento integrado e robô continuam posteriores. Ordens já decididas sem admissão permanecem legadas, sem backfill/republicação. A reserva de Stop compra continua pelo teto sobre o gatilho e a de Stop Limit pelo limite; conversão não aumenta reserva.

Admissões Market/Limit mantêm v1 byte a byte; somente stops usam `versao=2`, com `dados.precoGatilho` obrigatório. Eventos usam v2 quando a raiz ou qualquer ordem alvo (ambos os lados no negócio) é stop. A raiz v2 acrescenta `causacao` (admissão do comando raiz); `correlacao` identifica a admissão da ordem alvo, ou da agressora no negócio. Ambos os vínculos exigem ativo/sessão correspondentes; nenhum consumidor troca a correlação financeira por uma busca genérica. IDs continuam derivados de comando raiz/índice; `ocorridoEm` e `traceparent` permanecem os do comando raiz, inclusive em cascata. Eventos v1 históricos mantêm ordem, conteúdo e bytes; tópicos mantêm seus nomes. `STOP_PENDENTE` contém identidade e `precoGatilho`; `STOP_ACIONADO` contém identidade, `tipoExecutavel` (MARKET/LIMIT) e `sequencia` nova.

FO conserva tipo/validade originais: aceite de stop produz `AGUARDANDO_GATILHO`; pendência confirma gatilho; acionamento abre `ABERTA`, mesmo sem pendência anterior quando imediato. Negócio/oferta de stop antes de acionamento é incoerente. MO persiste gatilho e marcador `stop_acionado`; pendência/acionamento não movem recursos. Stop Limit acionado pode repousar; Stop acionado tem execução efetiva Market IOC apesar da validade original DIA. Excesso32 recebe `LIMITE_CASCATA` sem acionamento e com quantidade integral cancelada; excesso500 é rejeitado `LIMITE_STOPS_PENDENTES`. Demais terminais conservam progresso e liberam somente saldo restante. Não aceitar `EXPIRADA_DIA` por antecipação nesta fronteira.

Migrações aditivas: MO reserva recebe gatilho nulo e marcador falso por padrão, sem promover legado; FO amplia constraint de estado. Preservar metadados e deduplicação. Aplicação coordenada no laboratório: pausar processamento de negócio, aplicar migrações e atualizar os três serviços/consumidores compatíveis antes de retomar emissão v2; preservar logs/checkpoints/volumes. Falha exige correção para frente ou restauração consistente validada, nunca downgrade binário após efeitos v2 nem exclusão de reservas. Não se alega restauração testada sem execução. A necessidade de persistir ativação descarta inferi-la da tabela de hashes `matching_evento`, que não contém os fatos.

## Decisões OCO no núcleo — RF05c.3a (2026-10-21)

Refinamento com cinco pareceres medium independentes (arquitetura, QA, segurança, operação, viabilidade), todos **SEGUIR COM RESSALVAS**. Consenso: núcleo puro antes da integração; admissão atômica do par; cancelamento do Take Profit na captura irrevogável do gatilho; fato explícito de redução. Três regras de produto foram decididas pelo usuário em 2026-10-21, depois de ver as alternativas:

| Tema | Decisão |
| --- | --- |
| Conflito na admissão (usuário) | **Take Profit primeiro.** O TP é processado como Limit DIA agressor; cada negócio dele reduz o SL na mesma quantidade. Execução total, ou terminal por limite, encerra o SL. Se sobrar saldo, o SL reduzido é avaliado contra o último negócio **depois** do TP: acionável → captura e cancela o saldo do TP; senão fica pendente. Divergência dos pareceres (3 × 2 por "SL primeiro") resolvida pelo usuário. |
| Vagas (usuário) | O par ocupa **2** das 1000 ativas por conta/ativo e **1** das 500 pendentes por ativo. A vaga pendente é exigida já na admissão (conservador, porque só se sabe depois do TP se o SL ficará pendente). Sem vagas, o par inteiro é rejeitado, com um `ORDEM_REJEITADA` por perna e o mesmo motivo. |
| SL além de32 (usuário) | O TP é cancelado na captura e o SL termina com `LIMITE_CASCATA`; o par encerra. |

Consequências técnicas, sem nova regra de produto: qualquer terminal de uma perna que não seja a captura do SL encerra a irmã viva com `CANCELADA_OCO` (executada própria preservada, cancelada = saldo). O SL só executa depois de capturado e o TP só depois de admitido, por isso o TP nunca é reduzido; somente o SL recebe `ORDEM_REDUZIDA{reduzida,restante}`, mantendo a sequência original; na admissão a redução pode preceder o `STOP_PENDENTE`, porque o TP negocia antes de o SL ser guardado, e a RF05c.3b deve aceitar essa ordem no contrato. Conservação por perna: executada + soma das reduções + cancelada = quantidade original. A coerência alvo/gatilho do ADR-010 impede que negócios do próprio TP acionem o próprio SL. O núcleo repete as guardas: pernas distintas com mesma conta, intenção, ativo, lado, quantidade e sessão; TP Limit DIA; SL Stop/Stop Limit DIA; venda com alvo acima do gatilho, compra abaixo; compra com o mesmo preço máximo reservado nas duas pernas. Reentrega com as duas pernas já vistas não gera eventos; com uma só já vista, a inédita é rejeitada (`COMBINACAO_INVALIDA`) sem terminal para a outra. Fechamento expira as pernas vivas em sequência de chegada. Orçamento32/256 é do comando raiz; o par é uma raiz.

Recorte: somente domínio e testes. O adaptador continua com allowlist Market/Limit/stops simples e recusa serializar `ORDEM_REDUZIDA` e `CANCELADA_OCO`; admissão v3 do par, reserva com progresso por perna, liberação única no MO, migrações e `matching.xlsx` pertencem à RF05c.3b. O terminal de uma perna com a irmã viva não pode liberar a reserva do par; a RF05c.3b define a liberação por grupo antes de aceitar esses eventos.
