# Roadmap do Próspero

## Organização e conclusão

Uma tarefa principal por requisito funcional RF01–RF10. P01–P03 preparam a execução; V01 verifica os requisitos não funcionais de ponta a ponta. O número do RF é sua identidade, não uma exigência de implementação estritamente numérica. Cada tarefa recebe branch e PR próprios conforme [rules](rules.md).

Estados: **planejada**, **em andamento**, **aguardando revisão/merge**, **concluída**. Concluída exige validação, revisão e merge; um resumo local não substitui um PR. Datas e estimativas do roteiro original de 12 semanas não são compromisso deste roadmap.

**Estado atual:** P01 e P02 implementadas e validadas localmente, no [PR #1](https://github.com/mclovin137/Prospero/pull/1), aguardando revisão/merge. P04 em andamento: skills e CI implementadas, revisão independente em curso. P03 e RF01–RF10 permanecem planejadas. Repositório privado criado em `mclovin137/Prospero`; nenhuma tarefa é considerada concluída antes do merge.

## Fundação

### P01 — Governança e arquitetura

- Dependências: decisões do usuário; nenhuma tarefa técnica.
- Entrega: quatro ADRs, regras, inventário, roadmap, atualização do AGENTS, README, integração com memória, aviso de substituições na arquitetura original, modelo e resumo do PR.
- Aceite: links internos válidos; RF01–RF10 e RNF01–RNF08 rastreáveis; planejado distinto de implementado; decisões antigas identificadas; documentação revisada e PR mergeado.
- Verificação: revisão documental e checagem de links, metadados e cobertura do roadmap. Dispensa de planilha: alteração exclusivamente documental, ADR-004.

### P02 — Fundação técnica e containers

- Dependência: P01.
- Entrega: Maven Wrapper, quatro esqueletos Quarkus/Java 26 compiláveis isoladamente, JPA/migrações, Angular e Compose com todos os componentes escolhidos. Instrumentação básica, correlação, saúde, métricas e Swagger acessíveis localmente.
- Decidir antes do código: versões compatíveis, imagens fixadas, ferramenta de migração, acesso às cotações Redis, modelo de autenticação local e provisionamento dos atores/contas de teste. Não criar regra de negócio implícita para preencher lacunas.
- Aceite: build/test por serviço; quatro bancos e credenciais exclusivos; inicialização sem outro serviço Java; ambiente Compose saudável; caminhos OpenAPI reais; domínio sem dependência de infraestrutura. Documentar comandos e recursos locais observados.
- RNFs: RNF01, RNF03, RNF07, RNF08 como fundação, sem declarar metas finais comprovadas.

### P03 — Runner de planilhas

- Dependência: P02.
- Entrega: módulo CLI `.xlsx` no projeto frontend, container e exemplo. A instalação da memória foi antecipada para P04; captura automática depende da confiança manual nos hooks.
- Decidir antes do parser: esquema de colunas, referências, asserções, execução de cenários e limites operacionais. Usar APIs de preparação locais documentadas com invariantes, nunca acesso direto aos dados.
- Aceite: workbook independente com carga por API, IDs encadeados, status/headers/corpo, polling limitado e relatório de erro; repetição e execução isolada; arquivos inválidos falham antes dos efeitos. Validar gravação e recuperação de um handoff real do Prospero após reinício da memória.
- PR: incluir resumo textual do workbook. Isso entrega o mecanismo de teste; cenários financeiros entram nos RFs.

### P04 — Skills, memória e CI

- Dependências: P01 e P02; [refinamento](refinamentos/P04-skills-ci.md).
- Entrega: 14 skills adaptadas, proveniência, memória local persistente e workflows de revisão, testes e segurança com comentários em pt-BR.
- Aceite: revisão independente medium por especialidade, sem segredos publicados; CI executável em runner isolado e gratuito. A ativação exige provisionar esse runner; a máquina compartilhada com Horus não será registrada.
- Estado: implementação em andamento; checks locais não equivalem a execução no GitHub. Ver [guia CI](ci.md).

## Tarefas por requisito funcional

### RF01 — Consulta de ativos e livro

- Dependências: P02 e P03.
- Entrega: API e tela Angular com cinco ativos fictícios, último preço, variação, bid/ask, spread, volume, cinco níveis por lado e profundidade agregada.
- Definir: tickers, ticks, lotes, preços de referência e semântica de dados ausentes/defasados.
- Aceite/testes: contrato versionado, dados iniciais preparados por API, livro vazio e com níveis, indicadores calculados, cenário `.xlsx` e evidência visual. Atualizações por negócios serão integradas na RF06.

### RF02 — Envio e acompanhamento inicial de ordens

- Dependências: RF01.
- Entrega: boleta Angular, APIs de envio/consulta e estado persistido no FO; tipos Market, Limit, Stop e Stop Limit; validades Dia/IOC/FOK e vínculos OCO representados no contrato.
- Definir: combinações permitidas, estados, horários/fuso, idempotência de envio e significado de `201`/`202`.
- Aceite/testes: DTOs, OpenAPI, IDs/correlação e consulta de estado, erros estruturais, cenário de submissão pela planilha. Entrega limitada ao aceite/registro; não anunciar execução ou reserva antes de RF04/RF05.

### RF03 — Validação da ordem

- Dependências: RF02.
- Entrega: validação de lote, tick, Stop Limit com limite e túnel de ±10% do preço de referência.
- Definir: escalas, arredondamento/overflow, referência antes do primeiro negócio e para cotação defasada; túnel para ordens sem preço limite.
- Aceite/testes: limites e valores imediatamente fora deles, entradas inválidas e erro síncrono/estado de rejeição assíncrona coerentes; `.xlsx` e testes puros.
- RNF: RNF01.

### RF04 — Risco, reservas e SAGA

- Dependências: RF03.
- Entrega: MO com poder de compra, limites de posição/ordem e exposição vendida; reserva atômica; integração da SAGA/outbox com FO e deduplicação.
- Definir: reserva por tipo de ordem, proteção de limites concorrentes, consumo/liberação e timeout sem execução confirmada. Antes do matching real, usar testes internos de contratos, sem simular conclusão na API de negócio.
- Aceite/testes: saldo insuficiente, duas ordens concorrentes, reentrega sem reserva duplicada, rejeição e compensação segura; planilha mais testes de integração/concorrência.
- RNFs: RNF05, RNF06.

### RF05 — Matching e ciclo de execução

- Dependências: RF04.
- Entrega: algoritmo puro e adaptador Kafka com escritor único por ativo; preço-tempo, FIFO, preço passivo, parciais, IOC/FOK, stops, Stop Limit e OCO. Integrar execuções e reservas ao fluxo real.
- Definir: desempates, gatilhos/igualdade, cascatas, liquidez insuficiente, dimensionamento OCO após parciais, snapshots/offsets e recuperação após publicação abortada.
- Aceite/testes: mesmo log gera mesmos negócios; conservação de quantidade; comportamento de cada tipo/validade e recuperação comprovados. Usar propriedades, integração e planilhas via API.
- RNFs: RNF01, RNF02, RNF03, RNF05, RNF06; iniciar medição RNF04.

### RF06 — Formação e atualização do mercado

- Dependências: RF05.
- Entrega: último preço, bid/ask e VWAP por negócio, projeção Redis e atualização da interface. Alterações de ofertas sem negócio também atualizam profundidade/bid/ask.
- Definir: sessão do VWAP, atualização/expiração de cotação e bootstrap da projeção sem consulta a banco alheio.
- Aceite/testes: negócio, inserção e cancelamento alteram seus indicadores corretos; reentrega não soma volume duas vezes; dados ausentes/defasados são distinguíveis; planilha com espera limitada.
- RNFs: RNF01, RNF06, RNF07.

### RF07 — Posições e resultado financeiro

- Dependências: RF06.
- Entrega: cálculo e apresentação de long/short, preço médio, resultado realizado em redução e não realizado por cotação.
- Definir: regras de arredondamento e virada de posição, origem/ordenação dos eventos por conta e ativo.
- Aceite/testes: aumento, redução, zeragem e virada nos dois sentidos, reentrega sem duplicação, comparação com resultados explícitos da planilha; propriedades de conservação.
- RNFs: RNF01, RNF06.

### RF08 — Razão, histórico e liquidação D+2

- Dependências: RF07.
- Entrega: negócio, lançamentos e posição persistidos atomicamente no BO, razão em partidas dobradas, histórico e liquidação simulada; integração dos efeitos de caixa/custódia ao risco por eventos.
- Definir: calendário/feriados, estados de liquidação, falhas, distinção entre saldos disponível/reservado/liquidado e correções append-only. Estabelecer controle de tempo reproduzível por API no perfil local de testes.
- Aceite/testes: balanço contábil, conciliação de caixa/custódia, D+2 sem esperar dias reais, falha e retomada sem liquidação duplicada; planilha e integração.
- RNFs: RNF01, RNF05, RNF06.

### RF09 — Tempo real e trajetória da ordem

- Dependências: RF08.
- Entrega: atualizações via WebSocket, tela de ordens/posição e linha do tempo FO → MO → ME → BO, com correlação dos registros.
- Aceite/testes: estados coerentes para sucesso, parcial, rejeição e recuperação; reconexão recupera estado por API sem perder a visão atual; trace atravessa os quatro serviços. Planilhas validam estado e linha do tempo por HTTP; testes específicos validam WebSocket e a interface.
- RNF: RNF07.

### RF10 — Cancelamento e liberação de saldo

- Dependências: RF09.
- Entrega: API/tela de cancelamento de saldo Limit e stop ainda não acionado, com liberação correta e idempotente de reserva.
- Definir: conflitos com execução/acionamento, estados finais e cancelamento repetido. O saldo pode já ter sido parcialmente executado; apenas a parte elegível é cancelada.
- Aceite/testes: sem execução, parcial, stop pendente/acionado, duplicidade e corrida com negócio; nenhuma execução desfeita nem recurso liberado duas vezes. Planilha e testes de concorrência; atualizar casos da RF06 e linha do tempo.
- RNFs: RNF05, RNF06, RNF07.

## V01 — Validação integrada dos requisitos não funcionais

Dependências: RF01–RF10. Entregar cenários completos, carga reproduzível, documentação de execução local e roteiro de demonstração. Registrar hardware, limites dos containers, carga, aquecimento, volume de amostras e pontos medidos. Separar processamento da espera por contraparte de uma ordem Limit.

| Requisito | Evidência exigida | Tarefas que o constroem |
| --- | --- | --- |
| RNF01 — Precisão | Restrição automatizada de `double` financeiro, escalas definidas e resultados exatos | P02, RF03, RF05, RF07, RF08 |
| RNF02 — Determinismo | Replay de sequência conhecida com os mesmos negócios e recuperação consistente | RF05 |
| RNF03 — Ordenação | Escritor único por ativo e recuperação/rebalanceamento sem reordenar comandos | P02, RF05 |
| RNF04 — Latência | p99 boleta → negócio < 50 ms local e matching < 1 ms/ordem no cenário documentado | RF05, V01 |
| RNF05 — Reservas | Nenhuma ordem admitida ao matching sem reserva; falhas/cancelamentos sem perda ou duplicação | RF04, RF05, RF08, RF10 |
| RNF06 — Idempotência | Reentregas sem duplicar negócios, lançamentos, reservas ou posições | RF04–RF08, RF10 |
| RNF07 — Observabilidade | Trace por ordem, logs com `traceId`, métricas e quatro dashboards provisionados | P02, RF06, RF09 |
| RNF08 — Ambiente local | Compose reproduzível para todos os componentes, volumes e instruções sem serviços pagos | P02, P03 |

Os quatro dashboards cobrirão fluxo de ordens, risco/reservas, matching/mercado e back office/liquidação. Falha de uma meta deve gerar evidência e ajuste, não mudança silenciosa do requisito. Compensações, falhas de consumidor e reinícios serão exercitados por harness de infraestrutura; planilhas continuarão usando apenas APIs.

## Aceite comum às tarefas técnicas

Toda tarefa deve atualizar contratos, regras afetadas, inventário, roadmap e memória; executar testes proporcionais e a planilha quando elegível pelo ADR-004; documentar motivos, trade-offs, limitações e resultados no PR. Não substituir dependências futuras por comportamento de sucesso fictício. Evidências visuais entram quando houver alteração de interface.
