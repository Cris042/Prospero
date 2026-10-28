# ADR-013 — Matching, admissão pelo MO e ciclo de execução (RF05)

- Data: 2026-10-18.
- Estado: **aceita**; implementação em fatias RF05a–RF05d (roadmap). Emendada pelo [ADR-016](ADR-016-razao-liquidacao-d2.md): créditos ao comprador e ao vendedor adiados para a RF08 entram em D+2.
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

## Contrato da integração OCO — RF05c.3b (2026-10-21)

Refinamento complementar com cinco pareceres medium independentes, todos **SEGUIR COM RESSALVAS** e unânimes em dividir em **3b1** (consumidores tolerantes, migrações e serialização no ME, sem emissão nova) e **3b2** (MO emite a admissão do par, frontend, `matching.xlsx`, E2E). Nenhuma decisão de produto pendente: a coerência do ADR-010 faz do SL a perna de maior custo unitário na compra, e o núcleo exige o mesmo preço máximo nas duas pernas; portanto o restante reservado é sempre preço máximo × quantidade restante do grupo e cancelar o TP não deixa excedente a devolver. O ADR-012 "as duas pernas mudam de estado juntas" refere-se à decisão de risco; após a admissão cada perna tem progresso próprio.

Admissão `ORDEM_ADMITIDA` **v3** (MO → ME), uma mensagem por par, mesmo ID `admissao:<intenção>`, mesma raiz da v1. `dados = {ticker, lado, dataSessao, precoMaximo, pernas}`; `pernas` tem exatamente dois objetos, TP primeiro: `{papel=TAKE_PROFIT, ordem, tipo=LIMIT, validade=DIA, quantidade, precoLimite}` e `{papel=STOP_LOSS, ordem, tipo=STOP|STOP_LIMIT, validade=DIA, quantidade, precoLimite (null no Stop), precoGatilho}`. Leitura estrita: campos exatos, quantidades iguais, coerência alvo/gatilho por lado, limite da compra ≤ preço máximo, ordens distintas. O ME registra a intenção uma vez para as duas ordens e soma duas ao orçamento de ordens da sessão.

Eventos **v3** (ME → MO/FO) quando a raiz ou qualquer ordem alvo pertence a um par; v3 prevalece sobre v2. Raiz igual à v2 (`causacao` = admissão raiz; `correlacao` = admissão do alvo ou da agressora, igual para as duas pernas, por isso a perna sai sempre de `dados.ordem`). Novo `ORDEM_REDUZIDA{ordem,intencao,conta,reduzida,restante}`, somente v3; `ORDEM_ENCERRADA` v3 aceita também `CANCELADA_OCO` e `LIMITE_CASCATA`; `ORDEM_REJEITADA` v3 aceita `LIMITE_STOPS_PENDENTES`. v1/v2 permanecem byte a byte. O ME só aceita a admissão v3 junto com a serialização de todos os fatos OCO, de modo que nenhum comando válido vira mensagem venenosa.

MO: `V5` aditiva cria `reserva_perna` (PK `ordem`, FK `intencao`, papel, tipo, limite, gatilho, quantidade, executada, reduzida, cancelada, acionada, estado) e `reserva.oco`; a linha `reserva` continua dona do caixa/custódia do grupo, com `ordem` nula, e o legado continua identificado por `ordem` nula sem `oco`. Consumo da compra pelo preço máximo do grupo, troco imediato, como na ordem simples; reduções e captura não movem recursos. A perna terminal só se marca; a liberação do restante do grupo ocorre uma vez, no terminal que deixa o grupo sem perna viva. Guardas: redução só no SL não acionado e com restante igual ao do TP; `CANCELADA_OCO` do TP só com SL vivo e do SL só após terminal do TP; negócio do SL só acionado e do TP só antes da captura; conservação por perna.

FO: `V8` aditiva troca o índice único de `admissao_matching` por `(admissao_matching, papel_oco)`, acrescenta `quantidade_reduzida` e amplia `ordem_progresso`; novas pernas OCO recebem a admissão do grupo. Estados existentes: TP aceito `ABERTA`, SL aceito `AGUARDANDO_GATILHO`, redução muda só a quantidade vigente, `CANCELADA_OCO` vira `CANCELADA` com esse motivo. Pares já decididos sem admissão continuam legados, sem backfill; eventos sobre eles são recusados.

Ressalvas da validação da 3b1: reentrega parcial do par (uma perna já vista com outro conteúdo) continua parando o escritor do ME por "Admissão divergente", como já ocorre com ordens simples, porque o adaptador recusa antes do núcleo; o caminho `COMBINACAO_INVALIDA` do núcleo fica para chamadas diretas. A coerência grupo↔pernas (reserva `oco`, exatamente duas pernas, quantidade igual à inicial) é garantida pelo código e deve ser gravada atomicamente pela admissão da 3b2, com testes; o banco não a impõe por constraint entre tabelas.

Implantação: 3b1 (consumidores FO/MO e ME) antes da 3b2 (emissão pelo MO), com processamento pausado durante as migrações; sem downgrade após um comando v3 no log. Kafka PLAINTEXT continua risco aceito, agora ampliado: um `CANCELADA_OCO`/`ORDEM_REDUZIDA` forjado e coerente pode encerrar uma perna; as guardas de estado do MO limitam o dano até ACL/HMAC.

## Encerramento integrado de sessão — RF05c.4 (2026-10-21)

Refinamento com cinco pareceres medium independentes (arquitetura, QA, segurança, operação, viabilidade), todos **SEGUIR COM RESSALVAS**. Decisões do usuário em 2026-10-21, após ver as alternativas:

| Tema | Decisão |
| --- | --- |
| Autoridade (usuário) | Encerramento **manual por API administrativa no MO**, token de controle próprio (escopo `sessao:encerrar`, audiência própria), desligada por padrão fora do ambiente local; o MO grava um comando por ativo na outbox (`matching.comandos.v1`, chave = ticker). Sem agendador. |
| Sessão seguinte (usuário) | **Adiada.** A RF05c fecha com "uma sessão por log": encerrar é suportado; abrir nova data exige ambiente novo, sem apagar logs/checkpoints. Checkpoint/sessão por ativo fica para tarefa posterior. |

Consenso técnico: fatiar em **4a** (consumidores MO/FO aceitam `ORDEM_ENCERRADA{EXPIRADA_DIA}` e `ORDEM_REJEITADA{SESSAO_ENCERRADA, SESSAO_NAO_ENCERRADA}` nas versões da ordem alvo, com liberação única da reserva simples e do grupo OCO; sem emissão) e **4b** (comando `ENCERRAR_SESSAO`, ME publica as expirações com causação própria e replay, API do MO, workbook de encerramento executado por último, E2E). Expiração de perna OCO vale para TP vivo e SL pendente ou acionado; o grupo libera quando não sobra perna viva. O comando forjado no Kafka PLAINTEXT expiraria o livro inteiro de um ativo e liberaria suas reservas: o risco aceito aumenta e reforça ACL/HMAC no backlog. Contrato técnico (decidido na 4a, antes do código): eventos causados pelo encerramento usam **v4**, raiz igual à v2 com `causacao` = UUID determinístico de `encerramento:<ticker>:<dataSessao>` (verificável por MO/FO sem consulta) e `correlacao` = admissão da ordem alvo; v4 admite somente `ORDEM_ENCERRADA{motivo=EXPIRADA_DIA}` e o marco `SESSAO_ENCERRADA{expiradas}` (correlação = causação), emitido também com livro vazio. A causação determinística confere coerência, não autentica: qualquer produtor a calcula, como a causação v2. Um `EXPIRADA_DIA` forjado e coerente libera a reserva de ordem viva dentro do risco aceito do PLAINTEXT (guardas: ticker/data/conta/ordem, validade DIA, saldo exato, liberação única); a 4b deriva e confere o id do marco a partir da causação, para que um marco forjado não ocupe ids da deduplicação. Emissão (4b): `POST /api/v1/sessoes/{ticker}/{data}/encerramento` no MO (escopo `sessao:encerrar`, audiência do MO; chave HS256 local compartilhada com o FO por ora, flag `MO_ENCERRAMENTO_HABILITADO` desligada por padrão) grava na outbox o comando `ENCERRAR_SESSAO` v1 `{id, tipo, versao, origem, correlacao, ocorridoEm, traceparent, dados{ticker,dataSessao}}` com `id = correlacao = causação determinística`, idempotente (202 com `repetido`). O ME expira o ativo uma vez, publica as expirações v4 em ordem de chegada e o marco por último (ids `matching:<id>:<índice>`, derivados da causação); repetição e data diferente da sessão do log não geram eventos nem param o escritor nem trocam a sessão do checkpoint; o MO só aceita a data da sessão corrente (America/Sao_Paulo); consumidores conferem o id do marco derivado da causação. Rejeições de admissão tardia (`SESSAO_ENCERRADA`, `SESSAO_NAO_ENCERRADA`) são causadas pela própria admissão e seguem v1/v2/v3 da ordem, liberando a reserva como qualquer rejeição. v1/v2/v3 permanecem byte a byte.

## Robô formador de mercado e primeira medição RNF04 — RF05d (2026-10-21)

Refinamento com cinco pareceres medium independentes, todos **SEGUIR COM RESSALVAS**, unânimes em fatiar em **5d1** (medição) e **5d2** (robô) e em registrar o RNF04 sem atenuar. Os pareceres de operação, viabilidade e arquitetura identificaram o gargalo previsível: o relay da outbox do FO e do MO roda a cada 200 ms fixos, então o p99 boleta → negócio < 50 ms é inalcançável sem mudar o relay. Decisões do usuário em 2026-10-21:

| Tema | Decisão |
| --- | --- |
| Conta do robô (usuário) | **CONTA-004** nova por migração aditiva no FO e no MO (e na allowlist dos envelopes), carteira sintética grande (ordem de R$ 100 milhões e 100 mil ações por ativo), **não preparável**; teto de 1000 ordens mantido; ao esgotar, o robô para de forma limpa com estado `ESGOTADO` observável, sem recarga até a RF08. Emenda ADR-010/012. |
| Onde roda (usuário) | Ferramenta **Go stdlib** fora dos quatro serviços (como a API de carga, ADR-006), profile Compose opt-in desligado por padrão e endurecido, falando só com o FO por HTTP com token próprio `ordens:enviar`/`ordens:leitura` da CONTA-004, nunca injetado pelo Nginx. Não envia Stop/OCO; sem RF10, repõe só níveis consumidos e as ofertas DIA expiram no encerramento manual. |
| Matching < 1 ms (usuário) | Critério = **p99 de um timer em volta do núcleo dentro do `ProcessadorMatching`**, sob carga e ambiente documentados; microbenchmark do `MotorMatching` só como diagnóstico, sem declarar meta por média. |
| Relay 200 ms (usuário) | **Medir e otimizar depois**: a RF05d registra "medido, não cumprido" com causa raiz; a otimização do relay vira tarefa própria com ADR. |

Boleta → negócio mede, para o agressor, do `recebidaEm` no FO até a projeção do negócio no FO, contra oferta já em repouso, separando espera por contraparte (V01). Resultados com ambiente (CPU, RAM, WSL, limites dos containers, versões, SHA, plano, aquecimento, amostras, repetições) ficam numa seção "Medições" do HLD-004; números do runner hospedado da CI são indicativos (ambiente compartilhado) e não substituem medição em ambiente dedicado nem a V01.

### Detalhamento técnico do robô — RF05d.2 (2026-10-22)

Dentro das decisões acima, sem nova decisão de produto. Rodada complementar dispensada conforme `refinar-task` (tarefa RF05d já refinada com a fatia 5d2 no escopo; sem mudança material).

- **Grade determinística:** por ativo configurado (`TICKER:REFERENCIA:TICK:QUANTIDADE`), `N` níveis por lado em `referência ∓ (distância + k·passo)·tick`, Limit DIA, preços em centavos inteiros. O núcleo `Planejar` é puro (sem relógio nem I/O): o mesmo conjunto de ordens observado no FO gera o mesmo plano.
- **Reposição só do consumido:** nível com ordem viva (`RECEBIDA`/`RESERVADA`/`ABERTA`/`PARCIALMENTE_EXECUTADA`) não é tocado; nível cuja última ordem foi `EXECUTADA` volta; `CANCELADA` (inclusive expiração DIA) ou `REJEITADA` retira o nível na sessão. Recusa síncrona do FO (4xx sem ordem gravada, como tick/lote/túnel ou 409) também retira o nível na sessão, em memória. Sem RF10, o robô nunca cancela.
- **Idempotência:** chave `robo-AAAAMMDD-TICKER-{C|V}<preço em centavos>-g`, com `g` = execuções do nível na sessão. Reinício ou falha entre o POST e a leitura reproduz a chave, e o FO devolve a ordem existente; mudar a grade na sessão gera chaves novas, sem 409.
- **Estados:** `ESGOTADO` (`429 LIMITE_ORDENS`, orçamento de 1000 ordens ou rejeição `SALDO_INSUFICIENTE`/`CUSTODIA_INSUFICIENTE`/`LIMITE_ORDENS_ATIVAS`) dura até a virada da sessão; `AGUARDANDO_ENCERRAMENTO` impede nova grade enquanto houver oferta viva de sessão anterior (não dobra exposição nem reservas); `CREDENCIAL_RECUSADA` (401/403) é terminal até reiniciar com o token renovado; `FO_INDISPONIVEL` é transitório. O estado só é lido por `exec` do próprio binário (`--estado`), no loopback do container: Docker não publica porta de rede interna, e assim não há exposição no host.
- **Credencial:** `FO_TOKEN_ROBO` (CONTA-004, `ordens:enviar`/`ordens:leitura`, 7 dias, renovado por `preparar-ambiente.py`) entregue por secret em arquivo `.robo-local/token` (diretório 0700, arquivo 0444 para o UID 65532), porque serviço `read_only` não aceita secret por variável. O robô não tem `mercado:leitura` nem a chave de assinatura; fala com o FO por uma rede interna própria, sem Nginx. Riscos residuais aceitos: token de 7 dias sem revogação individual (vazamento exige regenerar `FO_TOKEN_ROBO` ou trocar a chave) e, com Kafka PLAINTEXT, um evento forjado coerente passa a alcançar também a carteira grande da CONTA-004.

## Cancelamento manual — RF10 em refinamento (2026-10-25)

Cinco pareceres medium independentes, registrados no roadmap, recomendaram seguir com ressalvas. Esta seção registra o alcance técnico proposto; não altera as decisões de produto já aceitas. A composição RF09b passou nos três gates em `22848c7` no PR #50; revisão/merge continuam pendentes.

FO autentica a conta, localiza a ordem e persiste a solicitação; MO continua único emissor legítimo de comandos ao ME. O escritor do ativo decide sobre o saldo vivo, depois de concluir o comando anterior e sua cascata. Negócios anteriores ao corte permanecem. MO só libera reserva diante de fato terminal coerente e confirmado; aceite HTTP, publicação, timeout ou estado do FO não liberam recursos. Na OCO a reserva é do grupo; terminais preservam a conservação por perna incluindo reduções.

O resultado da solicitação é distinto do estado financeiro da ordem. Recusar um cancelamento não produz `ORDEM_REJEITADA` de admissão nem encerra ordem viva. Identidade da solicitação, causação, repetição HTTP/evento e resultado durável serão especificados antes da emissão. Cancelamento anterior à admissão precisa de coordenação persistida no MO; ordenação por timestamp de outbox ou chegada entre tópicos não prova precedência. Não criar admissão ou reserva para completar um cancelamento.

**Regras de produto pendentes, perguntadas ao usuário:** saldo de Stop Limit já acionado que repousa no livro pode ser cancelado? A intenção manual em uma perna OCO cancela o grupo, apenas a perna ou permanece inelegível? Tipo público preservado e regras automáticas anteriores não resolvem essas escolhas. Nenhuma alternativa está aceita por inferência.

Compatibilidade: novos comandos, versões e motivos exigem preparação coordenada dos parsers FO/MO/ME/BO e projeção de mercado antes da emissão. O payload histórico v1–v4 e IDs existentes permanecem reproduzíveis. Depois de publicado um fato novo, retorno a consumidor incompatível não é recuperação segura: preservar bancos/logs e corrigir para frente; não declarar restauração executada sem evidência. Limites de log/ativas existentes não aumentam por rotina; repetição não pode gerar comandos ilimitados.

Alternativas descartadas pelo contrato vigente: FO publicar diretamente no ME contradiz a admissão pelo MO; liberar reserva ao aceitar HTTP viola a autoridade do escritor e pode duplicar recursos; reutilizar rejeição de admissão para recusar pedido confunde dois fatos e pode alterar finanças. Reusar remoção FIFO e progresso das reservas conserva as fronteiras atuais, com custo de evolução coordenada e recuperação testada em todos os consumidores.

Evidências exigidas: corridas controladas de execução/cancelamento/acionamento/fechamento, reserva compra/venda/grupo conservada, repetição e publicação parcial, replay misto antigo/novo, cotação sem negócio fictício, trajetória por conta e confirmação financeira distinta da aceitação. Estado, agentes e fatiamento no roadmap; regras finais no PRD após resposta do usuário.

### Contrato técnico proposto da solicitação

Tréplica arquitetural confirmou que reutilizar os tópicos FO↔MO preserva as fronteiras com menos infraestrutura. `risco.comandos.v1` passa a distinguir `CANCELAMENTO_SOLICITADO` de avaliação de risco, mantendo chave conta e tipo/versionamento próprios. `risco.resultados.v1` recebe `RESULTADO_CANCELAMENTO`, distinto de `RESULTADO_RISCO`. Outboxes e consumidores existentes são ampliados explicitamente; nenhum tipo novo é emitido antes da preparação de todos os leitores.

| Etapa | Responsabilidade proposta |
| --- | --- |
| HTTP FO | `POST /api/v1/ordens/{ordem}/cancelamentos`, conta pelo JWT, permissão `ordens:enviar`, chave de idempotência; 202 com operação durável e consultável por GET restrito à mesma conta/ordem, com `ordens:leitura`, `no-store` e 404 indistinguível para alvo alheio/inexistente. Proxy mantém método/rota exatos, Origin/Host e limite de POST. DTO estrito, sem conta, preço ou quantidade cancelável fornecidos pelo cliente. |
| Repetição | Chave HTTP é por conta/operação e conteúdo canônico. Conteúdo divergente devolve 409. Chaves diferentes para o mesmo alvo canônico referenciam uma operação financeira, sem novos comandos ilimitados; a regra do alvo OCO aguarda produto. |
| Coordenação MO | Pedido anterior ao risco permanece pendente. Rejeição definitiva do risco finaliza recusa sem inventar admissão. Existindo admissão, o comando de cancelamento depende explicitamente da sua publicação confirmada por ack e persistida na outbox; existência de reserva/timestamp não satisfaz a dependência. Queda após ack e antes da marcação repete admissão idêntica antes de cancelar. |
| Decisão ME | `CANCELAMENTO_SOLICITADO` no log do ativo é processado em sequência. Resultado determinístico; terminais afetados precedem `CANCELAMENTO_DECIDIDO`, último evento do comando na mesma partição. Recusa não produz terminal financeiro. Catálogo/versionamento novos serão fixados com as regras de elegibilidade antes do núcleo. |
| Confirmação MO | Consome os terminais antes da decisão, valida vínculo, todos os terminais/quantidades esperados e o estado da reserva, e grava resultado/outbox na transação. A decisão precisa identificar os terminais esperados; ausência, divergência ou reserva incompatível impedem confirmação. Inconsistência bloqueia antes de confirmar. No sucesso, a confirmação significa cancelamento aplicado e efeitos sobre a reserva confirmados; não promete aumento de disponível em todo resultado. Na recusa, confirma somente o processamento do pedido, sem alegar aplicação financeira. |
| Apresentação FO | Distingue solicitação pendente, decisão ME e confirmação/recusa MO na trajetória; estado cancelado pode chegar antes da aplicação no MO. WebSocket invalida, HTTP recupera. Nenhum timeout vira sucesso financeiro. |

Identidades separadas: pedido canônico, comando de cancelamento, admissão alvo, ordem/grupo e IDs de eventos. Correlação financeira continua ligada à admissão; causação dos novos terminais aponta ao comando de cancelamento. Trace não é identidade. Mesmo pedido/comando/evento com assinatura divergente é inconsistência; repetição idêntica recupera o resultado sem nova liberação, notificação ou revisão.

Alternativas consideradas: tópicos próprios de cancelamento são coerentes, porém não há necessidade de isolamento que justifique novo par neste laboratório; rejeitar pedido precoce simplifica a espera, mas cria recusa transitória e exige permitir nova operação futura. Mantê-lo pendente com dependência durável evita essa ambiguidade. Não reutilizar a mesma identidade permanentemente para uma recusa transitória que impediria cancelamento futuro.

### Preparação interna independente — RF10a (2026-10-26)

Rodada complementar com cinco pareceres medium independentes reduziu a primeira entrega à conservação da proveniência viva. Stop Limit convertido conserva o tipo público original; dissolver o vínculo operacional OCO não transforma a origem da perna sobrevivente em simples. Um índice por identidade no estado do ativo guarda dados imutáveis e acompanha inclusão e baixa da contagem ativa, termina com a ordem e é reconstruído pelas admissões existentes no replay. Guarda tipo original, intenção, conta, ativo, sessão e identidades TP/SL; não guarda outro histórico de comandos nem muda IDs, prioridades ou bytes v1–v4. Consulta interna retorna somente dados imutáveis.

Alternativa: propagar um novo parâmetro por admissão, conversão, fila e repouso dispersaria a mudança pelo algoritmo; o índice vivo concentra o ciclo de vida sem alterar o contrato de admissão. Asserções de identidade nos terminais verificam correspondência com a conta ativa. Fechamento limpa o índice junto às estruturas vivas. Limites existentes permanecem; não há registro de solicitações, política de cancelamento, emissão, parser novo ou efeito em reservas nesta fatia. As propostas de transporte acima e as duas decisões do usuário continuam pendentes.

## Decisões parciais de cancelamento manual — RF10 (2026-10-27)

O usuário autorizou integrar os PRs #49/#50 e respondeu às perguntas de produto: **sim** para cancelar saldo de Stop Limit já acionado; **não** para encerrar o grupo OCO inteiro quando se solicita cancelamento de uma perna. RF09 foi integrada, com gates aprovados, nos merges `9a3f31d` e `bb2ad5b`.

A elegibilidade do Stop Limit considera somente saldo vivo; negócios anteriores ao corte não são desfeitos, e a liberação financeira continua dependendo dos fatos aplicados pelo MO. A origem Stop Limit é preservada após a conversão, conforme a preparação RF10a. Isso registra uma decisão de produto, sem afirmar implementação.

A resposta sobre OCO rejeita a alternativa de cancelar o grupo inteiro; **ainda não escolhe** entre cancelar somente a perna solicitada e impedir cancelamento manual de OCO. Pergunta complementar apresentada ao usuário, sem resposta até este registro. Não transformar a irmã em ordem simples, dissolver proteção ou liberar reserva de grupo por inferência. As decisões automáticas OCO anteriores permanecem aplicáveis até existir contrato manual completo.

Esta seção atualiza as duas lacunas registradas no refinamento histórico acima: Stop Limit resolvida, OCO parcialmente resolvida. Transporte continua proposta técnica; comportamento dependente da escolha OCO aguarda resposta, evolução coordenada dos consumidores e revisão complementar antes do código.

## Regra final OCO e contrato interno RF10b1 (2026-10-27)

O usuário esclareceu: **não permitir cancelamento manual** de OCO. Isso encerra a dúvida da seção anterior; toda origem OCO é inelegível, mesmo se a relação operacional foi dissolvida e apenas uma perna permanece viva. Cancelamentos automáticos anteriores permanecem. Stop Limit simples acionado conserva a elegibilidade do seu saldo vivo, conforme a resposta anterior do mesmo dia.

Cinco perspectivas medium independentes recomendaram seguir com ressalvas antes do código; consolidação no roadmap. RF10b1 é somente núcleo puro, sem parser, transporte, API, esquema ou efeito MO. Entrada `AlvoCancelamento` imutável contém ordem, intenção, conta, ativo e sessão, sem preço/quantidade escolhidos pelo solicitante. Resultado fechado `ResultadoCancelamentoManual`, com precedência de avaliação: `SEM_SALDO_VIVO`, `ALVO_DIVERGENTE`, `ORIGEM_OCO` e `APLICADO`. Primeiro procurar estado e origem existentes; depois conferir identidade completa, origem OCO e estrutura viva antes de retirar.

`APLICADO` devolve exatamente um fato interno `SaldoCanceladoManual` com executada acumulada e cancelada positiva igual ao saldo retirado. Esse tipo **não implementa `Evento`**, não acrescenta motivo ao catálogo serializável e não pode entrar nos emissores atuais. Recusa devolve zero fatos, sem `ORDEM_REJEITADA`, abertura de sessão, registro em `vistas`, dissolução/redução OCO ou mutação financeira. Origem viva sem ordem na estrutura correspondente ou contagem incoerente é falha de invariante, antes da remoção. O escritor decide entre comandos completos, após a cascata anterior.

A retirada preserva negócios, último preço, sequências e FIFO das demais ordens; termina com a proveniência e recupera sua vaga interna. Repetição posterior pode devolver `SEM_SALDO_VIVO`, sem segundo terminal/efeito. `SessaoMatching` hoje conserva admissões, não resultados de cancelamento; identidade canônica, causação, resultado durável, deduplicação por comando e replay Kafka da retirada continuam dependências da integração futura. Não criar cache ilimitado ou afirmar recuperação financeira nesta fatia.

Alternativa descartada nesta preparação: acrescentar imediatamente terminal/motivo ao catálogo Kafka deixaria um novo fato serializável antes da evolução coordenada de FO/MO/ME/BO. O fato interno separado torna a fronteira explícita. Sem novo limite financeiro, dependência ou estrutura de histórico. Aceites RED/GREEN, históricos v1–v4 e limites no roadmap; XLSX dispensada somente enquanto não houver API/emissão/efeito externo, conforme ADR-004.

## Coordenação integrada e recibo de admissão — RF10c (2026-10-27)

Cinco pareceres medium independentes, registrados no roadmap, recomendaram seguir com ressalvas. A proposta técnica anterior de POST com aliases de chaves é substituída por uma operação canônica: **PUT `/api/v1/ordens/{id}/cancelamento`**, consultada por GET na mesma URI, com unicidade persistida por ordem. Corpo ausente ou objeto JSON estritamente vazio; nenhum preço, quantidade ou conta. Repetição deve recuperar a mesma operação sem nova outbox; antes do risco ela aguarda, sem recusa transitória definitiva. FO deriva o alvo da ordem autenticada, preserva 404 indistinguível e pode recusar OCO com422 antes de criar operação. O202 confirma apenas persistência da solicitação. API e emissão ainda não estão implementadas.

O caminho fica dividido em preparação dos contratos/leitores/recuperação, seguida da coordenação/emissão/API e evidência integrada. Primeiro incremento da preparação, **RF10c1**, fecha a prova durável da publicação da admissão no MO; não emite cancelamento nem acrescenta endpoint. A outbox atual expurga publicados após um dia. Seu desaparecimento, a existência da reserva e timestamps de criação não provam ACK.

### Contrato de dados RF10c1 antes do SQL

V8 aditiva cria `admissao_publicacao`, com `admissao` como PK/FK para a coluna única existente `reserva.admissao`; tópico/tipo fixos `matching.comandos.v1`/`ORDEM_ADMITIDA`, chave, payload textual exato, traceparent, criação e confirmação opcional. Payload limitado a16384 bytes UTF-8 por `octet_length`. Uma linha por admissão conhecida, sem histórico de pedidos/aliases e sem novos tetos financeiros. Dados imutáveis do recibo correspondem à mensagem original; `confirmada_em` conserva o primeiro ACK persistido. A comparação não normaliza JSON nem altera bytes/headers.

O registro nasce na mesma transação da reserva/admissão/outbox. O publicador captura a mensagem efetivamente enviada e, após ACK, grava sua confirmação na mesma transação que marca a outbox. Identidade, tópico, tipo, chave, payload e traceparent precisam corresponder entre snapshot confirmado, recibo e outbox; ausência/divergência aborta a transação. Queda após ACK e antes do commit deixa ambas as marcações pendentes e reentrega os mesmos bytes. Outros tipos da outbox não ganham recibo. O expurgo continua removendo somente a outbox temporária, preservando o comprovante.

Backfill V8 usa somente linhas de outbox ainda existentes ligadas inequivocamente a `reserva.admissao`, copiando bytes, headers e `publicado_em` real. Vínculo existente com tipo/tópico/chave/tamanho incompatíveis recusa a migração com mensagem genérica, sem aceitar ACK por filtro silencioso. Linha expurgada ou reserva legada sem identidade de admissão não ganha recibo ou publicação presumida. No futuro cancelamento, recibo conhecido sem ACK aguarda; legado sem prova recuperável produz recusa operacional explícita `ADMISSAO_NAO_COMPROVADA`, sem liberar reserva. A operação canônica conservará essa recusa até reconciliação específica, sem reconstrução ou reenvio automáticos; não se promete cobertura retroativa de dados sem evidência.

### Aplicação, recuperação e evidências

Aplicar expansão com o processamento pausado. Backfill percorre apenas a outbox retida e o vínculo indexável por UUID; esse trabalho de migração limitado não justifica varreduras contínuas. Não apagar reservas, logs ou volumes. Antes de novos fatos de cancelamento, a aplicação anterior pode voltar conservando a tabela aditiva, mas não gera recibos durante esse período: os dados produzidos por ela precisam de reconciliação explícita antes de depender da nova prova. Correção de esquema usa nova migração ou restauração consistente; nenhuma restauração é atribuída sem ensaio. Não editar V1–V7 nem usar Flyway Undo pago. Depois da emissão futura v5, a recuperação continua para frente com leitores compatíveis.

QA exclusivo deve comprovar RED/GREEN e PostgreSQL16/JTA real: migração V7→V8, legado publicado/pendente/expurgado, limite por bytes, mesma transação de registro, ACK/rollback/reentrega/primeiro ACK, divergência antes de confirmar, expurgo e demais tipos preservados. MockProducer controla os ACKs; não é prova de autenticação Kafka ou capacidade. Sem API nova, XLSX dispensada somente neste incremento interno conforme ADR-004. API completa exigirá workbook duas vezes, autorização/proxy, reserva/ordem/mercado/trajetória e quedas recuperáveis.

## Catálogo v5 e leitores de cancelamento — RF10c2 (2026-10-27)

Cinco pareceres medium independentes (arquitetura, QA, segurança, operação e viabilidade) recomendaram seguir com ressalvas e fixaram o catálogo abaixo antes do código. Esta fatia entrega somente contratos, fixtures compartilhadas, leitores e o lado ME. **Nenhum produtor de produção emite os tipos novos**: FO e MO não serializam `CANCELAMENTO_SOLICITADO`, `RESULTADO_CANCELAMENTO` nem `CANCELAR_ORDEM`, e nenhuma flag os liga. O ME só os emite como resposta a um `CANCELAR_ORDEM`, que ninguém produz ainda.

### Catálogo

| Tópico (chave) | Tipo, versão e origem | `dados` | Identidade |
| --- | --- | --- | --- |
| `risco.comandos.v1` (conta) | `CANCELAMENTO_SOLICITADO` v1, `front-office` | `ordem`, `intencao`, `ticker`, `dataSessao` | `id = uuid("cancelamento-solicitado:" + ordem)`; `correlacao = uuid("admissao:" + intencao)`, a admissão alvo |
| `risco.resultados.v1` (conta) | `RESULTADO_CANCELAMENTO` v1, `middle-office` | `ordem`, `resultado` (`CONFIRMADO` ou `RECUSADO`), `motivo` | `id = uuid("resultado-cancelamento:" + ordem)`; mesma correlação. `motivo` nulo em `CONFIRMADO`; em `RECUSADO` pertence a `SEM_SALDO_VIVO`, `ALVO_DIVERGENTE`, `ORIGEM_OCO`, `ADMISSAO_NAO_COMPROVADA`, `RISCO_REJEITADO` |
| `matching.comandos.v1` (ticker) | `CANCELAR_ORDEM` v1, `middle-office` | `AlvoCancelamento`: `ordem`, `intencao`, `conta`, `ticker`, `dataSessao` | `id = uuid("cancelamento:" + ordem)`; mesma correlação. Raiz sem `conta`, como `ENCERRAR_SESSAO`: a conta vive em `dados` |
| `matching.eventos.v1` (ticker) | envelope **v5**, `matching-engine`, com `causacao = id` do comando | `ORDEM_ENCERRADA` com motivo `CANCELADA_MANUAL` (`ordem`, `intencao`, `conta`, `motivo`, `executada` acumulada, `cancelada` = saldo retirado, maior que zero) e `CANCELAMENTO_DECIDIDO` (`ordem`, `intencao`, `conta`, `resultado`, `terminal`) | ids `uuid("matching:" + comando + ":" + indice)`; correlação = admissão alvo |

`CANCELAMENTO_DECIDIDO.resultado` pertence a `APLICADO`, `SEM_SALDO_VIVO`, `ALVO_DIVERGENTE`, `ORIGEM_OCO`. `terminal` é o id do `ORDEM_ENCERRADA` em `APLICADO` e `null` nas recusas. Em `APLICADO` o terminal tem índice 0 e a decisão índice 1; numa recusa a decisão tem índice 0. A decisão é sempre o último evento do comando. A v5 é exclusiva desses dois tipos: o motivo `CANCELADA_MANUAL` é proibido em v1–v4 e os tipos v5 são proibidos fora da v5. O texto "o nome do comando de matching é `CANCELAMENTO_SOLICITADO`" da proposta anterior fica corrigido: cada tópico tem nome próprio para evitar ambiguidade entre solicitação (FO→MO) e comando (MO→ME). Os leitores recalculam `causacao = uuid("cancelamento:" + ordem)`, `correlacao` e os ids; qualquer divergência é inválida.

`SaldoCanceladoManual` continua fora de `Evento`: o serializador v5 do ME o converte direto no envelope, sem acrescentar `CANCELADA_MANUAL` ao enum de domínio.

### Decisão por leitor

| Leitor | `ORDEM_ENCERRADA CANCELADA_MANUAL` | `CANCELAMENTO_DECIDIDO` |
| --- | --- | --- |
| FO ordens e trajetória | Marca a ordem `CANCELADA` com o motivo, se não há OCO, a executada e o saldo conferem exatamente e a causação é a derivada; Stop não acionado é elegível (PRD) | Valida identidade e, em `APLICADO`, exige a ordem já cancelada manualmente; registra o id para deduplicação e o marco `CANCELAMENTO_DECIDIDO` na trajetória, sem alterar estado |
| FO cotação | `Retirar` da oferta, como os demais terminais; Stop pendente não repousa e nada muda | Ignora sem negócio fictício |
| BO | Valida a coerência v5 e ignora | Valida e ignora |
| MO reservas | Valida estritamente. Sem operação de cancelamento persistida **bloqueia** com `CANCELAMENTO_SEM_COORDENACAO`, sem tocar reserva nem deduplicação. Com operação `COMANDO_EMITIDO`, libera a reserva uma vez e vai a `TERMINAL_APLICADO` ([coordenação no MO](#coordenação-do-cancelamento-no-mo--rf10c2b-2026-10-27)) | Idem sem operação. Com operação: `APLICADO` exige `TERMINAL_APLICADO` e confirma; as três recusas exigem `COMANDO_EMITIDO` e recusam sem tocar a reserva |
| ME | Produz os dois eventos | Produz |
| Parser de risco do MO | `CANCELAMENTO_SOLICITADO` válido entra na coordenação do MO; reconhecido e inválido continua bloqueando a partição | n/a |
| Parser de risco do FO | `RESULTADO_CANCELAMENTO` reconhecido bloqueia a partição | n/a |

Os consumidores de `risco.*` usam `ConsumoKafka` não financeiro, que descarta mensagem inválida confirmando o offset, com retenção de 7 dias. Perder um pedido ou resultado de cancelamento seria silencioso, então os tipos novos lançam `ParticaoBloqueadaException`: o offset não avança, a tentativa repete com espera crescente, nunca há descarte por falha persistente e a métrica `prospero_consumo_bloqueadas_total{topico,motivo}` conta cada ocorrência. Um alerta Prometheus cobre bloqueio e qualquer descarte em `risco.*`. Os motivos são constantes: `CANCELAMENTO_SOLICITADO_SEM_COORDENACAO` e `RESULTADO_CANCELAMENTO_SEM_LEITOR` para mensagem válida, `..._INVALIDO` para a de tipo reconhecido que falha na leitura estrita (versão, campo extra, id ou correlação não derivados, conteúdo após o JSON). JSON ilegível ou acima de 16 KiB não permite reconhecer o tipo e segue o descarte existente; um emissor legítimo nunca produz esses casos. O leitor de matching do MO usa o mesmo mecanismo com `CANCELAMENTO_SEM_COORDENACAO`, sem gravar nada: o consumidor financeiro fica fora de prontidão e retoma sozinho no mesmo offset quando a versão com coordenação for implantada. Parar a partição é intencional: a recuperação é implantar essa versão, não descartar. O alerta `ProsperoRiscoConsumoBloqueadoOuDescartado` cobre `prospero_consumo_bloqueadas_total` e `prospero_consumo_descartadas_total` em `risco.*`, inclusive a primeira exposição sem baseline.

### Deduplicação no ME

`CANCELAR_ORDEM` é deduplicado pelo id do comando em `SessaoMatching`, num mapa `id → AlvoCancelamento` limitado a 30.000 entradas e reconstruído pelo replay do log (todo comando é reprocessado na ordem). Segunda entrega com o mesmo conteúdo não produz evento nem efeito; mesmo id com alvo diferente é inconsistência e bloqueia o escritor, como na admissão. Ordem desconhecida devolve `SEM_SALDO_VIVO`, sem abrir sessão nem criar estado, pela precedência RF10b1. O checkpoint do log usa a sessão persistida e só a fixa com a do comando se o log ainda não tiver uma, como em `ENCERRAR_SESSAO`.

### Errata ao refinamento anterior (D1–D4)

O desenho do bloco RF10 simplificou quatro pontos do texto aceito em "Contrato técnico proposto da solicitação" e "Coordenação integrada". Preservam as invariantes: reserva liberada uma vez, nada liberado por HTTP ou timeout, OCO inelegível.

- **D1.** "Pedido anterior ao risco permanece pendente" no MO é substituído por pendência no FO (`AGUARDANDO_RISCO`). O FO só emite `CANCELAMENTO_SOLICITADO` depois de aplicar `RESULTADO_RISCO`. O MO nunca vê cancelamento anterior à decisão de risco; se a decisão faltar, bloqueia a partição.
- **D2.** A espera pelo ACK da admissão no MO não usa varredura nem timer: quem grava o primeiro ACK promove a operação `AGUARDANDO_ACK` e grava o `CANCELAR_ORDEM` na outbox, na mesma transação.
- **D3.** Uma linha de operação por ordem nos dois serviços (PK = ordem). Repetição devolve a mesma linha, sem nova outbox; recusa é final. Elimina chaves de idempotência e aliases.
- **D4.** A flag `prospero.cancelamento.emissao-habilitada` protege só a criação de operações, fica `false` por padrão e responde 503 `CANCELAMENTO_INDISPONIVEL`. Operações já persistidas continuam sendo promovidas. É um interruptor de novas emissões, não uma máquina de estados.

D1 a D3 passam a valer no MO com a coordenação (seção seguinte); D4 e a pendência do FO entram com a fatia da API.

### Compatibilidade e recuperação

Os leitores desta fatia precisam estar implantados em FO, BO, MO e ME antes de qualquer emissão futura. Depois da primeira emissão v5 não há retorno de imagem: preservar bancos e logs e corrigir para frente. Os fatos históricos v1–v4 e seus ids permanecem byte a byte; o ME mantém os vetores congelados (3 de 3) e ganha replay misto v1–v5. Esta fatia não tem migração SQL em FO, BO nem MO: as colunas de motivo não têm CHECK. A tabela de coordenação do MO chega na V9, descrita a seguir.

## Coordenação do cancelamento no MO — RF10c2b (2026-10-27)

O MO passa a coordenar o cancelamento: aceita `CANCELAMENTO_SOLICITADO`, escreve `CANCELAR_ORDEM` e `RESULTADO_CANCELAMENTO` e libera a reserva com o terminal do ME. Nenhum produtor liga ainda: o MO só emite essas mensagens em resposta a um `CANCELAMENTO_SOLICITADO`, e o FO continua sem emiti-lo até a fatia da API. Vale o catálogo acima; aqui ficam o estado, a ordem dos locks e os desvios do texto anterior.

### Tabela `cancelamento_operacao` (V9, aditiva)

Uma linha por ordem (PK = ordem). Estados: `AGUARDANDO_ACK`, `COMANDO_EMITIDO`, `TERMINAL_APLICADO`, `CONFIRMADO`, `RECUSADO`. Os CHECKs garantem que `motivo` existe só em `RECUSADO` e pertence ao conjunto fechado de cinco, que `terminal` existe só em `TERMINAL_APLICADO` e `CONFIRMADO`, que `decisao` (o resultado do ME) é `APLICADO` só em `CONFIRMADO` e igual ao `motivo` nas recusas do ME, e que `decidida_em` existe só nos estados finais. `solicitacao` e `comando` são únicos; `assinatura` (SHA-256 de id, ordem, intenção, conta, ativo e sessão) distingue repetição de inconsistência.

Dois desvios pequenos do desenho: `intencao` referencia `decisao_risco` e não `reserva`, porque a ordem rejeitada pelo risco não tem reserva e ainda assim precisa de uma recusa `RISCO_REJEITADO`; e a tabela guarda `traceparent`, pois a promoção no ACK acontece em outra transação e outra thread e o `CANCELAR_ORDEM` precisa continuar o trace do pedido.

### Solicitação

`SolicitarCancelamentoActor` roda numa transação, na ordem de locks conta, depois recibo da admissão, a mesma de `AvaliarRiscoActor` e do publicador.

1. Se a operação da ordem já existe, assinatura igual devolve a mesma, sem nova outbox; assinatura diferente bloqueia a partição (`CANCELAMENTO_INCONSISTENTE`).
2. Sem decisão de risco para a intenção, bloqueia (`CANCELAMENTO_SEM_DECISAO_DE_RISCO`), conforme D1.
3. Recusa local, na primeira regra que falhar: decisão `REJEITADA` (`RISCO_REJEITADO`); reserva sem ordem, sessão ou recibo da admissão derivada (`ADMISSAO_NAO_COMPROVADA`); reserva de grupo OCO (`ORIGEM_OCO`); ordem, conta, ativo ou sessão divergentes (`ALVO_DIVERGENTE`); reserva `ENCERRADA` (`SEM_SALDO_VIVO`). Toda recusa grava `RECUSADO` e o `RESULTADO_CANCELAMENTO` na mesma transação.
4. Caso contrário trava a linha do recibo (`SELECT ... FOR UPDATE`). Com `confirmada_em` preenchido grava `COMANDO_EMITIDO` e o `CANCELAR_ORDEM` (chave = ativo, tópico `matching.comandos.v1`). Sem ele grava `AGUARDANDO_ACK`.

### Promoção no primeiro ACK (D2)

`PublicacoesAdmissaoJpa.confirmar` chama a porta `PromotorCancelamento`, declarada em `mensageria.dominio`, na mesma transação que marca a outbox. A implementação do contexto de cancelamento muda `AGUARDANDO_ACK` para `COMANDO_EMITIDO` e grava o `CANCELAR_ORDEM`. Como `mensageria` só conhece a porta, o ArchUnit proíbe qualquer dependência dela para `cancelamento`. O lock do recibo faz da corrida solicitação × ACK uma serialização: quem chega depois bloqueia no recibo e enxerga o efeito do outro, então sai exatamente um `CANCELAR_ORDEM` e nenhuma operação fica presa em `AGUARDANDO_ACK`. A queda depois do ACK e antes do commit desfaz marcação e promoção juntas; a reentrega repete as duas. Não há varredura nem timer, e nenhum tempo decorrido promove, recusa ou libera: um ACK que nunca vem deixa a operação pendente e visível, não vira sucesso nem recusa.

### Confirmação e liberação única

Dentro de `RepositorioMatchingJpa.aplicar`, depois da deduplicação por id do evento e com os locks da conta já tomados, na ordem conta, reserva, operação:

- `ORDEM_ENCERRADA CANCELADA_MANUAL` exige operação `COMANDO_EMITIDO`, causação igual ao `comando`, correlação igual à admissão, e ordem, conta, ativo e sessão iguais aos da operação. A reserva precisa ser de ordem simples, viva, com `executada = inicial - restante` e `cancelada = restante`; vale também para Stop pendente, que a regra geral de terminais não admite. Chama o mesmo `encerrar` dos demais terminais, cuja guarda impede a segunda liberação, e move a operação para `TERMINAL_APLICADO` guardando o id do terminal.
- `CANCELAMENTO_DECIDIDO APLICADO` exige `TERMINAL_APLICADO` com o mesmo `terminal` e vai a `CONFIRMADO`, gravando um único `RESULTADO_CANCELAMENTO CONFIRMADO`.
- `SEM_SALDO_VIVO`, `ALVO_DIVERGENTE` e `ORIGEM_OCO` exigem `COMANDO_EMITIDO` e `terminal` nulo, vão a `RECUSADO` com o mesmo motivo e não tocam a reserva.

Qualquer incoerência (estado, causação, alvo, quantidades) bloqueia a partição sem liberar. Evento v5 sem operação correspondente continua bloqueando com `CANCELAMENTO_SEM_COORDENACAO`. Nenhuma chamada HTTP, timer nem job participa: só o terminal do ME libera saldo, o que o ArchUnit protege proibindo `java.net`, JAX-RS, scheduler e concorrência no pacote `cancelamento`.

### Compatibilidade

A V9 é aditiva e não é lida por versões anteriores; ao voltar imagem a tabela fica. Como o MO só emite em resposta a um pedido que ainda ninguém faz, a ordem de implantação (leitores de todos os serviços antes de qualquer emissão) continua a do bloco. O volume do V8 sem recibo (ordens abertas antes dele) terá o cancelamento recusado de forma permanente com `ADMISSAO_NAO_COMPROVADA`.

### Evidências e ADR-004

Fixtures v5 compartilhadas em `contratos/v5/`, com manifesto SHA-256 conferido por `scripts/verificar-contratos.py` e por testes de cada serviço, cobrem válidos e um corpus negativo (versão, campo extra ou duplicado, mais de 16 KiB, conteúdo após o JSON, UUID e data não canônicos, enum fora, ticker diferente da chave, origem errada, quebra de linha e marcador de log no payload). O ME prova que seus bytes reais são iguais às fixtures. Planilha XLSX é dispensada pelo ADR-004 nesta fatia porque nenhum produtor ativo emite os tipos novos nem há API ou efeito externo; a API completa exigirá workbook duas vezes.

## API, interruptor e tela do cancelamento — RF10c3 (2026-10-28)

Última fatia do cancelamento manual: o FO passa a emitir `CANCELAMENTO_SOLICITADO` e a expor a operação canônica. O catálogo, a coordenação do MO e os leitores v5 seguem como nas seções anteriores; aqui ficam o que o FO faz e os desvios pequenos.

### Contrato HTTP e persistência no FO

`PUT /api/v1/ordens/{id}/cancelamento` (escopo `ordens:enviar`) e `GET` na mesma URI (`ordens:leitura`, `Cache-Control: no-store`). A conta vem do JWT. Corpo ausente ou exatamente `{}`; qualquer outro corpo, JSON malformado ou chave duplicada recebe 400 `CORPO_CANCELAMENTO_INVALIDO`, e corpo com tipo diferente de `application/json` também. Ordem inexistente, de outra conta ou com identificador malformado recebe o mesmo 404 `ORDEM_NAO_ENCONTRADA`; o GET sem pedido responde 404 `CANCELAMENTO_NAO_ENCONTRADO` também para ordem alheia. Ordem com `grupoOco` (inclusive a perna que sobrevive) recebe 422 `ORDEM_OCO_NAO_CANCELAVEL` antes de criar operação. O 202 só confirma que a operação foi persistida; o corpo traz `estado`, `decisao`, `motivo` e os instantes.

A tabela `cancelamento_operacao` (V12, aditiva) tem uma linha por ordem (PK = ordem), estados `AGUARDANDO_RISCO`, `SOLICITADO`, `DECIDIDO`, `CONFIRMADO`, `RECUSADO`, e os mesmos CHECKs de motivo do MO. O PUT trava a linha da ordem, devolve a operação existente se houver (cabeçalho `X-Cancelamento-Repetido: true`, sem nova outbox) e, caso contrário, cria uma: `AGUARDANDO_RISCO` sem outbox quando a ordem ainda está `RECEBIDA` (D1), ou `SOLICITADO` com a linha da outbox e o marco `CANCELAMENTO_SOLICITADO` na mesma transação. O resultado do risco que chega depois promove (RESERVADA) ou recusa com `RISCO_REJEITADO` (REJEITADA) as operações que aguardavam, na ordem de locks ordem, operação, revisão da conta. Ordem já terminal no FO ou rejeitada ainda emite o pedido: quem recusa é o MO (`SEM_SALDO_VIVO`, `RISCO_REJEITADO`), sem regra duplicada no FO.

As transições só avançam por ranking (`AGUARDANDO_RISCO` < `SOLICITADO` < `DECIDIDO` < `CONFIRMADO` = `RECUSADO`), porque `CANCELAMENTO_DECIDIDO` (ME) e `RESULTADO_CANCELAMENTO` (MO) vêm de tópicos diferentes. Repetição, regressão, resultado de outra conta e resultado sem operação não alteram nada. O estado `CONFIRMADO` só vem do MO; o FO nunca libera reserva. O consumidor de `risco.resultados.v1` aplica `RESULTADO_CANCELAMENTO` válido e continua bloqueando a partição para o inválido (`RESULTADO_CANCELAMENTO_INVALIDO`); a conta do envelope precisa ser igual à chave Kafka.

### Interruptor (D4) e telemetria

`prospero.cancelamento.emissao-habilitada` (variável `FO_CANCELAMENTO_EMISSAO_HABILITADA`) fica `false` por padrão e protege só a criação de operações: 503 `CANCELAMENTO_INDISPONIVEL`, sem persistir nem emitir. Repetição e GET continuam. O `docker-compose.yml` local liga a flag porque constrói FO, MO, ME e BO juntos; qualquer outro ambiente precisa ter os leitores v5 nos quatro serviços antes de ligá-la. Métricas: `prospero.cancelamento.solicitacoes{resultado}`, `prospero.cancelamento.resultados{resultado,aplicado}` e o gauge `prospero.cancelamento.pendente.segundos.max` (idade da operação mais antiga sem resultado final, índice parcial `cancelamento_pendente`).

### Proxy e tela

Nginx e proxy de desenvolvimento ganharam a rota exata `ordens/{uuid}/cancelamento` com GET e PUT; PUT exige `Origin` local e divide a cota de escrita das ordens, POST/DELETE e PUT nas demais rotas continuam 403. O token continua só no servidor. A lista "Minhas ordens" mostra "Cancelar" para ordens não OCO sem estado terminal, o estado da operação (só `CONFIRMADO` afirma a liberação) e o erro de cada linha em `role="alert"`.

### Evidência e limites

Testes REST Assured sobre repositório em memória, IT PostgreSQL da V12 e dos hooks de risco/matching, `cancelamento.xlsx` pelo runner (ADR-004) em duas execuções na CI e E2E Playwright. A V12 é aditiva; ao voltar a imagem a tabela fica e as operações persistidas deixam de ser promovidas, então a correção segue para frente. ❓ LACUNA: o prazo para uma operação sem resultado (ACK da admissão que nunca vem, partição bloqueada) não tem regra de negócio; ela fica pendente e visível pelo gauge, sem timeout, recusa nem liberação.
