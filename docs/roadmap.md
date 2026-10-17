# Roadmap do Próspero

## Organização e conclusão

Uma tarefa principal por requisito funcional RF01–RF10. P01–P03 preparam a execução; V01 verifica os requisitos não funcionais de ponta a ponta. O número do RF é sua identidade, não uma exigência de implementação estritamente numérica. Cada tarefa recebe branch e PR próprios conforme [rules](rules.md).

Estados: **planejada**, **em andamento**, **aguardando revisão/merge**, **concluída**. Concluída exige validação, revisão e merge; um resumo local não substitui um PR. Datas e estimativas do roteiro original de 12 semanas não são compromisso deste roadmap.

**Estado atual:** P01 e P02 concluídas: [PR #1](https://github.com/mclovin137/Prospero/pull/1) integrado em 2026-10-16 por autorização explícita do usuário, após validação e revisão locais. A implementação da P04 foi integrada pelo [PR #2](https://github.com/mclovin137/Prospero/pull/2); sua ativação e validação da CI no GitHub continuam pendentes de runner dedicado. P05, P07 e P08 concluídas: PRs #3, #4 e #5 integrados em 2026-10-17 por autorização explícita do usuário, após conferência independente das evidências locais. P03 concluída, integrada pelo [PR #9](https://github.com/mclovin137/Prospero/pull/9) em 2026-10-17 por autorização explícita do usuário; P06 e RF01–RF10 permanecem planejadas. Repositório publicado como público por autorização do usuário; nenhum release foi solicitado.

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

- Estado: concluída — [PR #9](https://github.com/mclovin137/Prospero/pull/9) integrado em 2026-10-17 por autorização explícita do usuário; merge `a6edf4f`. Validação local e três pareceres independentes concluídos; CI remota ainda sem execução.
- Refinamento prévio (2026-10-17): brief comum para cinco agentes independentes medium `p03_arquitetura`, `p03_qa`, `p03_seguranca`, `p03_operacao`, `p03_viabilidade`; todos **SEGUIR COM RESSALVAS**. Memória consultada em `prospero/Prospero`, sem handoff pendente, confrontada com main e PRD/HLD/ADR-004. Nenhuma API financeira existe; não há divergência documental por funcionalidade planejada.
- Decisões técnicas/aceite antes do parser: contrato v1 no README do frontend, três abas estritas, validação integral antes de HTTP, referências tipadas isoladas por cenário, polling GET limitado, falha sem rollback. ExcelJS 4.4.0 com preflight yauzl 3.4.0 e worker limitado; configuração confiável de origens, sem redirecionamento, sem dados brutos em relatório. UUID transitivo requer override corrigido e verificação de compatibilidade. Bibliotecas de parser são dependências runtime auditadas; não entram no bundle Angular.
- Plano de evidência: QA exclusivo comprova RED/GREEN com servidor HTTP descartável (POST/captura/polling, repetição/isolamento e negativos); workbook técnico consulta apenas `/api/v1/sistema` real. Revisão e segurança exclusivas; build e checks frontend/documentais, audit e container opt-in limitado. Testes backend dispensados se não houver alteração backend. Evidência do mecanismo não prova negociação financeira; PRD sem alteração de requisito, HLD/ADR/libs/roadmap atualizados proporcionalmente.
- Evidências locais (2026-10-17): RED 39 falhas comportamentais por ausência de implementação; GREEN final no container **49/49**, cobrindo carga POST/IDs/polling/isolamento, precisão por lexema, ZIP expansivo/entradas, fórmulas, redirecionamento, limites, credenciais e CLI/sinais. Workbook real quatro cenários/oito passos passou, repetição passou e seleção FO passou. Angular **5/5**, build, format, verificadores documentais/estrutura, Compose e npm audit produção (zero) passaram. Smoke completo `verificar-ambiente.py` passou. E2E Playwright: primeira tentativa bloqueada por `libasound.so.2`; complemento independente **4/4 passou** após extrair pacote Ubuntu `libasound2t64` 1.2.15.3-1ubuntu1.1 em diretório temporário e usar `LD_LIBRARY_PATH` somente nessa execução, sem instalar no host. Chromium 156.0.8078.4/revisão 1248. Capturas agora usam `testInfo.outputPath` em `frontend/test-results/` ignorado, sem recriar `docs/evidencias/`; revisão e segurança focused sem achados. Nenhuma mudança visual. Sem verificação Java nova porque backend não mudou.
- Pareceres finais medium separados: `p03_arquitetura` (pr-audit), `p03_qa` (testes reais) e `p03_seguranca` (security-audit). Precisão JSON, expansão por captura e localização de erros foram corrigidos e revalidados; sem achados pendentes no escopo. CI GitHub continua sem runner dedicado; resultados locais não equivalem a gates remotos. Gitleaks fixado por digest no commit da implementação: nenhum segredo encontrado; Trivy/imagens não executados nesta rodada; escopo/limites completos no PR.
- Dependência: P02.
- Entrega: módulo CLI `.xlsx` no projeto frontend, container e exemplo. A instalação da memória foi antecipada para P04; captura automática depende da confiança manual nos hooks.
- Decidir antes do parser: esquema de colunas, referências, asserções, execução de cenários e limites operacionais. Usar APIs de preparação locais documentadas com invariantes, nunca acesso direto aos dados.
- Aceite: workbook independente com carga por API, IDs encadeados, status/headers/corpo, polling limitado e relatório de erro; repetição e execução isolada; arquivos inválidos falham antes dos efeitos. A persistência e recuperação da memória já foram verificadas na P04.
- PR: incluir resumo textual do workbook. Isso entrega o mecanismo de teste; cenários financeiros entram nos RFs.

### P04 — Skills, memória e CI

- Dependências: P01 e P02; refinamento e evidências preservados no PR #2/histórico Git.
- Entrega: 14 skills adaptadas, proveniência, memória local persistente e workflows de revisão, testes e segurança com comentários em pt-BR.
- Aceite: revisão independente medium por especialidade, sem segredos publicados; CI executável em runner isolado e gratuito. A ativação exige provisionar esse runner; a máquina compartilhada com Horus não será registrada.
- Estado: implementação integrada pelo [PR #2](https://github.com/mclovin137/Prospero/pull/2); ativação pendente. Checks locais não equivalem a execução no GitHub. Ver [guia CI](rules.md#ci-e-runner).
- Preparação da ativação (2026-10-17), [PR #15](https://github.com/mclovin137/Prospero/pull/15) em rascunho: checagem `scripts/verificar-runner-dedicado.py` e roteiro na seção CI das rules, com atestado explícito de isolamento, requisitos locais e recusa sem modificar o host. QA independente: nove testes aprovados, verificadores documentais/estrutura e sintaxe shell aprovados; execução real recusou corretamente este host sem atestado. Revisão de arquitetura e segurança independentes sem bloqueios; testes Python incorporados ao gate de revisão. Consulta remota encontrou zero runners registrados; o Docker do host atual compartilha Horus e havia 3183 MiB de RAM disponível na inspeção, portanto ele não será registrado. Próximo passo externo: obter acesso a host/VM dedicado gratuito, validar sob usuário próprio e só então registrar e executar os três gates no GitHub. Preparação local não conclui a ativação nem altera o aceite.

### P05 — Núcleo decimal exato no middle office

- Dependência: P02; refinamento preservado no PR #3/histórico Git.
- Estado: concluída — [PR #3](https://github.com/mclovin137/Prospero/pull/3) integrado em 2026-10-17.
- Entrega: representação técnica escalada autoral, conversão exata com sinal explícito e testes de limites. Proveniência e limites de reuso no [HLD do MO](hlds/HLD-003-middle-office.md).
- Aceite: sem arredondamento silencioso, overflow e expoentes extremos recusados, domínio puro e build MO independente. Sem nova API; dispensa XLSX conforme ADR-004.
- Limite: tipos financeiros, escalas de moeda/tick, risco, reservas e cálculo de posições permanecem nas RF03–RF08.

### P06 — API de carga progressiva e observabilidade

- Dependências: P02; cenários financeiros dependem de P03 e RFs correspondentes.
- Estado: planejada quanto à implementação; **Go aceito pelo usuário em 2026-10-17**, após a comparação com Rust. [ADR-006](adrs/ADR-006-api-carga.md) registra o aceite da linguagem; [HLD-006](hlds/HLD-006-api-carga.md) apresenta arquitetura e pesquisa solicitadas. Framework/motor permanecem em avaliação técnica, sem serviço, build ou carga Go executados. [PR #10](https://github.com/mclovin137/Prospero/pull/10) documental integrado na branch P03 (`304d32d`), com conteúdo incorporado ao `main` pelo PR #12 em 2026-10-17; a consolidação preserva também esse commit de merge.
- Refinamento prévio: brief comum de API técnica isolada, execução única, rampas limitadas, cancelamento, allowlist, credenciais externas, contagens/latência e fontes reais. Cinco perspectivas medium separadas (arquitetura, QA, segurança, operação e viabilidade) recomendaram seguir com ressalvas; memória `prospero/Prospero` confrontada com PRD/HLD/ADRs/Git. Após aceite de Go, rodada complementar documental nos agentes `p03_arquitetura`, `p03_qa`, `p03_seguranca`, `rf01_operacao` e `rf01_viabilidade`, todos seguir com ressalvas: HLD próprio, fontes oficiais e README com carga planejada versus runner XLSX existente.
- Pesquisa: preferência inicial `net/http` + Huma/humago para contrato; comparação chi/Gin/Fiber; context/errgroup, rate limiter separado do modelo, métricas Prometheus, testes httptest/race/fuzz. Motores Vegeta Go/k6/Fortio/nativo comparados com os mesmos critérios de limite/cancelamento/contagem. Vegeta MaxBody não comprova teto de tráfego, pois a revisão examinada drena o restante; framework/motor não foram instalados nem aprovados por popularidade ou benchmark externo.
- Complemento solicitado: desempenho e consistência sob falha/retorno de MS, WAL/fsync/group commit e diagnóstico `pg_test_fsync`; Kafka RF3/minISR2/acks all/idempotência apenas como perfil futuro, separado do broker único atual. JWT HS256 fixado no servidor, rejeição de `none` e testes negativos sem bypass. [ADR-007](adrs/ADR-007-coletor-gc.md) propõe baseline G1/comparação Parallel/ZGC, sem alterar runtime ou inventar medições. Aceite financeiro depende das RFs e reconciliação por APIs; alta vazão HTTP não o substitui.
- Próxima ação técnica: experimento restrito de contrato e motor, fixar versões, preflight confiável e limites, depois implementar. Pendências preservadas no HLD: teto também em modo fechado, contagens disjuntas, drenagem/término, retenção e fontes reais de recursos. Não reduzir aceite nem tratar dado ausente como zero. A linguagem está resolvida; não repetir a pergunta Go/Rust. Nenhuma nova definição financeira da RF01 é presumida por essa escolha.
- Entrega: controle local de execuções por API, rampas limitadas, cancelamento, gerador isolado e dashboards Grafana com fontes reais.
- Aceite: carga oferecida versus atingida, erros/timeouts/descartes e latência reproduzíveis; recursos do gerador e sistema separados; filas/eventos observáveis quando implementados; limites e isolamento preservados.
- Definir: localização do controlador, ferramenta geradora, orçamento, proteção das APIs e coletores. Não cria automaticamente um quinto serviço de negócio.
- Replicação/consenso: N/A na topologia atual; quatro bancos independentes não são réplicas. Topologia replicada exige decisão futura e orçamento local.

- API `/api/v1`, DTOs/OpenAPI para iniciar, consultar e cancelar execução; `202`/Location é candidato conforme ADR-003, não conclusão. Estados candidatos: aguardando, executando, concluída, cancelada e falha. Definir contrato e localização do controle antes do código.
- Uma execução ativa global, separada do processamento financeiro; estágios com teto de duração, VUs, RPS e concorrência. Limitar requisições, conexões, filas, tamanho de resposta e retenção. Cancelamento manual/automático e término seguro do gerador se o controlador falhar.
- Alvos por identificador de serviço/rota permitida, sem URL, porta, comando, redirecionamento ou header arbitrário; proteger chamadas indevidas do navegador mesmo em loopback. Preparação/tráfego/limpeza só por APIs e restritos à execução, sem acesso direto a bancos/Kafka ou apagar volumes.
- Distinguir chegada aberta (RPS) de usuários fechados (VUs); contar oferecidas/agendadas, iniciadas, concluídas, em voo, erros HTTP, timeouts, não iniciadas e descartadas, com janela e conservação das contagens. Separar transporte de resultado de negócio e espera no agendador/saturação do gerador, sem esconder atrasos.
- Registrar hardware, aquecimento, duração, relógio, amostras, denominadores/tratamento de erros em p50/p95/p99/máximo. VUs não comprovam usuários autenticados. Preflight de RAM/CPU/disco inclui gerador/telemetria; abortar sem orçamento, sem interromper Horus nem duplicar stack para obter números.
- Provisionar painéis técnicos de carga/HTTP, CPU/memória/GC/disco/rede/descritores/pools, filas/lag/idade outbox, logs/traces e defasagem de projeções quando existentes. Definir coletores reais antes; dado ausente não é zero. Labels limitadas a serviço/rota/método/status/fase; IDs de usuário/execução só em correlação sanitizada, com retenção limitada.
- Etapa A: baseline técnico dos endpoints existentes, sem alegar capacidade financeira. Etapa B: cenários de negócio após RFs/P03. Dashboards técnicos complementam os quatro financeiros da V01; fontes de métricas e ferramentas ainda dependem de definição.


### P07 — Agente de diagramas C4

- Dependência: P01; refinamento preservado no PR #4/histórico Git.
- Estado: concluída — [PR #4](https://github.com/mclovin137/Prospero/pull/4) integrado em 2026-10-17 após P05.
- Entrega: perfil portátil e adaptador de agente quando comprovadamente suportado; fontes do Próspero, esforço medium, modelo herdado, proposta sem escrita/execução.
- Aceite: exercício real com fonte suficiente e lacunas/conflitos; não inventar arquitetura implementada nem exigir FDD ou serviços pagos.

### P08 — Documentação proporcional e playbook

- Dependências: P01 e P07; refinamento preservado no PR #5/histórico Git.
- Estado: concluída — [PR #5](https://github.com/mclovin137/Prospero/pull/5) integrado em 2026-10-17 após P07, incluindo a correção do índice em docs/README.md.
- Entrega: guia PRD/HLD/ADR, PRD e cinco HLDs (ecossistema + quatro MS), substituição dos dois documentos anteriores, índice em docs/README.md, skills gerar-prd/gerar-hld e playbook único a partir da análise das cinco referências Minerva.
- Aceite: revisão de aderência com evidências, requisitos preservados, documentos sem duplicação, links/hashes e exercícios independentes das skills; sem mudanças de runtime ou requisitos financeiros.
- Dispensa XLSX: documentação sem endpoint novo, ADR-004.

## Tarefas por requisito funcional

### RF01 — Consulta de ativos e livro

- Estado: planejada; refinamento prévio em andamento com cinco pareceres consolidados, implementação dependente de semântica e parâmetros ainda não decididos. Refinamento documental integrado pelo [PR #11](https://github.com/mclovin137/Prospero/pull/11) em 2026-10-17, merge `f058205`; P03 já integrada. Isso não aprova os parâmetros financeiros pendentes nem implementa a RF01.
- Brief prévio (2026-10-17): consulta FO e tela Angular com cinco ativos/indicadores/cinco melhores níveis, preparação e limpeza por API, XLSX isolado e evidência visual; sem matching, reservas ou execução antecipados. Cinco perspectivas independentes medium: `p03_arquitetura` (arquitetura), `p03_qa` (QA), `p03_seguranca` (segurança), `rf01_operacao` (operação) e `rf01_viabilidade` (viabilidade), todas **SEGUIR COM RESSALVAS**. Memória `prospero/Prospero` consultada e confrontada com PRD, HLD-001/002/004, ADR-001–004 e Git; nenhuma aprovação dos parâmetros encontrada.
- Fronteira consensual: ME será dono do livro executável; FO expõe projeção de leitura. Preparação local de snapshot sintético pode demonstrar a consulta, com origem explícita, sem admitir ordens, publicar negócios, criar reservas ou alimentar silenciosamente risco futuro. Não implementar matching no FO para preencher a RF01; RF06 integrará eventos reais.
- Proposta enviada ao usuário, **sem aceite**: ALFA3/BETA3/GAMA3/DELTA3/OMEGA3; referências iniciais BRL 10/20/30/40/50, tick 0,01, lote 1, quantidades inteiras. Último preço não é substituído pela referência; ausência de negócio, livro vazio e fonte indisponível são estados distintos. Não usar esses valores como seed definitivo enquanto a pergunta permanecer sem resposta.
- Contratos técnicos candidatos, ainda em desenho: consultas `/api/v1/ativos` e `/api/v1/ativos/{ticker}/livro`, DTOs com decimais textuais, origem/instante/revisão da projeção e ausência explícita. API de preparação restrita ao perfil local, credencial fora do Angular/Git, schema estrito, namespace UUID selecionado explicitamente e autorização por execução. Atualizar snapshot integralmente validado de forma atômica, sem combinar indicadores de revisões diferentes. Caminhos/schema finais dependem das decisões abaixo.

| Ponto a fechar antes do código dependente | Critério e alternativa a avaliar |
| --- | --- |
| Último e referência | Origem própria para referência inicial, último negócio e observação sintética. Referência da tela não decide a âncora do túnel da RF03. |
| Variação | Definir absoluta/percentual, base, fórmula, escala e arredondamento; desconhecido não vira zero. |
| Volume | Definir quantidade negociada versus financeiro, janela/sessão/fuso; quantidade das ofertas não equivale a volume negociado. |
| Profundidade | Agregação por preço, bids decrescentes/asks crescentes, acumulados e distinção entre cinco níveis exibidos e total conhecido do livro; não preencher níveis inexistentes com quantidade zero. |
| Ausência e defasagem | Diferenciar snapshot inexistente, livro válido vazio/unilateral, último ausente e fonte indisponível; definir instante, idade máxima e política de timestamp futuro. |
| Preparação e autorização | Definir provisionamento, consulta pública ou protegida, token/escopo do namespace, limites de entradas/bytes/execuções e limpeza idempotente restrita. UUID e loopback não constituem autorização. |
| Projeção e recuperação | HLD-002 prevê Redis; FO tem apenas rede, sem cliente/configuração. Redis atual usa AOF, 48 MiB e allkeys-lru: AOF não impede evicção. Escolher perda explícita/repreparo por API ou fonte própria de recuperação, sem introduzir banco+cache por rotina. Alternativas temporárias em memória/PG próprio exigem avaliar e registrar impacto no HLD antes de adotá-las. |
| Precisão e coerência | Gramática/tamanho/escala/limites antes da conversão, overflow da agregação e política para duplicatas/livro cruzado. Não compartilhar implementação do MO nem calcular/ordenar dinheiro por `number` Angular. |

- Plano de evidência após resolver lacunas: testes puros de agregação/ordenação/corte/acumulados/precisão; REST/OpenAPI/Problem Details para sucesso, dados ausentes, entrada inválida, permissão e namespace; snapshot inválido sem escrita parcial e leituras coerentes sob concorrência. XLSX via P03 para vazio, unilateral, níveis/agregação/truncamento, rejeições e dois namespaces concorrentes; repetir/selecionar/inverter cenários e excluir um preservando o outro. P03 interrompe o cenário na primeira falha: DELETE no final não garante limpeza, então definir recuperação/TTL ou harness com limpeza exclusivamente por API em `finally`.
- UI/E2E futuro: carregando, vazio, parcial, sintético, defasado e erro; mudança de seleção não exibe resposta tardia do ativo anterior. Verificar teclado/foco/nome acessível, desktop/mobile, ausência de overflow e capturas reais comparadas à API. Nenhum desses testes RF01 foi executado nesta rodada de refinamento.
- Próxima ação dependente: fechar parâmetros e semânticas, escolher preparação/projeção aderente ao HLD e então implementar contrato → domínio/Actor/adaptador → Angular → XLSX/E2E, com migração somente se armazenamento escolhido exigir. Trabalho independente realizado nesta rodada limita-se ao contrato candidato, lacunas e caminho de evidência; PRD/HLD/ADRs não foram alterados como se houvesse aceite.
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
- Entrega: validação de lote, tick, Stop Limit com limite e túnel de ±10% do último preço; tratar referência inicial/defasada conforme definição da RF03.
- Definir: escalas, arredondamento/overflow, referência antes do primeiro negócio e para cotação defasada; túnel para ordens sem preço limite.
- Aceite/testes: limites e valores imediatamente fora deles, entradas inválidas e erro síncrono/estado de rejeição assíncrona coerentes; `.xlsx` e testes puros.
- RNF: RNF01.

### RF04 — Risco, reservas e SAGA

- Avaliação adicional: write skew e proteção da invariante agregada no HLD-003; linha materializada/atualização condicional, guardião em protocolo de leitura/bloqueio ou serializable são alternativas a validar. Lock isolado não renova snapshot antigo; teste futuro com duas conexões PostgreSQL reais, sem alegar concorrência comprovada no perfil técnico atual.
- Avaliação documental solicitada em 2026-10-17: políticas alteráveis sem deploy; [ADR-008](adrs/ADR-008-politicas-risco.md) compara parâmetros versionados/CEL Java/DSL restrita, e HLD-003/README explicam aplicação proposta. Sem compilador, dependência, migração, reserva ou API de risco implementada; RF04 segue dependente da RF03. [PR #12](https://github.com/mclovin137/Prospero/pull/12) documental integrado ao `main` em 2026-10-17 por autorização explícita do usuário, merge `b6b7499`; ADR-008 continua proposta.
- Refinamento: brief de expressões puras, aritmética exata, precedência/curto-circuito, versão imutável, limites, ativação e reserva atômicas; memória confrontada com código P05/PRD/ADRs/HLD003. Cinco agentes medium independentes (`p03_arquitetura`, `p03_qa`, `p03_seguranca`, `rf01_operacao`, `rf01_viabilidade`) recomendaram seguir com ressalvas: parâmetros primeiro quando bastam, DSL como proposta, nenhum desempenho presumido e nenhuma substituição da proteção transacional. DSL é alternativa nova à orientação de evitar engine genérica, sem revogá-la por hipótese.
- Dependências: RF03.
- Entrega: MO com poder de compra, limites de posição/ordem e exposição vendida; reserva atômica; integração da SAGA/outbox com FO e deduplicação.
- Definir: reserva por tipo de ordem, proteção de limites concorrentes, consumo/liberação e timeout sem execução confirmada. Antes do matching real, usar testes internos de contratos, sem simular conclusão na API de negócio.
- Aceite/testes: saldo insuficiente, duas ordens concorrentes, reentrega sem reserva duplicada, rejeição e compensação segura; planilha mais testes de integração/concorrência.
- RNFs: RNF05, RNF06.

### RF05 — Matching e ciclo de execução

- Medição antes da otimização: protocolo JMH do núcleo e async-profiler/carga integrada no HLD-004. Ferramentas/versões e estruturas permanecem candidatas; alocação/serialização não são gargalos medidos. Preservar replay/correção e orçamento; nenhum benchmark executado nesta avaliação.
- Dependências: RF04.
- Entrega: algoritmo puro e adaptador Kafka com escritor único por ativo; preço-tempo, FIFO, preço passivo, parciais, IOC/FOK, stops, Stop Limit e OCO. Integrar execuções e reservas ao fluxo real.
- Definir: desempates, gatilhos/igualdade, cascatas, liquidez insuficiente, dimensionamento OCO após parciais, snapshots/offsets e recuperação após publicação abortada.
- Aceite/testes: mesmo log gera mesmos negócios; conservação de quantidade; comportamento de cada tipo/validade e recuperação comprovados. Usar propriedades, integração e planilhas via API.
- RNFs: RNF01, RNF02, RNF03, RNF05, RNF06; iniciar medição RNF04.

### RF06 — Formação e atualização do mercado

- Dependências: RF05.
- Entrega: último preço, bid/ask e VWAP do dia por negócio, projeção Redis e atualização da interface. Alterações de ofertas sem negócio também atualizam profundidade/bid/ask.
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

- Avaliação transversal solicitada em 2026-10-17: cinco temas em agentes distintos — `parecer_jobs_liquidacao`, `parecer_write_skew`, `parecer_matching_medicao`, `parecer_mensagens_senhas` e `parecer_historico_lotes`. Brief comum: jobs/efeito incerto; invariante concorrente; medir antes de otimizar; HMAC/mTLS/Argon2; histórico frio e SQL por conjuntos. Além do tema individual, emitiram perspectivas medium de arquitetura, QA, operação, segurança e viabilidade. Todos seguir com ressalvas; memória confrontada com PRD/ADRs/HLDs/código antes das alterações. Detalhes e fontes nos HLDs existentes/README, sem pasta de pesquisa. [PR #13](https://github.com/mclovin137/Prospero/pull/13) documental integrado à branch do PR #12 por autorização do usuário em 2026-10-17; revisão, QA e segurança finais independentes sem achados pendentes. Merge `5833783`; conteúdo integrado ao `main` pelo PR #12 em 2026-10-17.
- HLD-005 detalha proposta de jobs persistentes, claim e revisão de processando abandonado como desconhecido, sem reexecutar automaticamente; efeito local/conclusão/outbox atômicos. Notas de corretagem são lacuna de produto, sem geração/envio adotados. Calendário, transições, posse e evidências de reconciliação continuam anteriores ao código.
- Histórico: preservar verdade de negócio, arquivo verificável/restaurável e leitura sob demanda; mídia/partições/retenção não escolhidas sem volume e orçamento. Relatórios por conjuntos em sumários/projeções; não atualizar negócios/razão originais. Não remover dados necessários a pendências, deduplicação ou replay. Testes de corte, retomada, integridade, acesso e restauração são futuros.
- Segurança transversal: HLD-001 compara HMAC de evento versus TLS mútuo cliente–broker/ACLs e Argon2id condicional a senhas locais. São mecanismos candidatos, sem desvio MO → BO, mudança do Kafka PLAINTEXT ou autenticação implementada. Adoção material exige decisão/ADR e contratos antes do código.
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

Dependências: P06 e RF01–RF10. Entregar cenários completos, carga reproduzível, documentação de execução local e roteiro de demonstração. Registrar hardware, limites dos containers, carga, aquecimento, volume de amostras e pontos medidos. Separar processamento da espera por contraparte de uma ordem Limit.

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

A V01 também entregará alertas locais orientados às metas e sintomas de negócio, com condições, janela e teste de disparo/recuperação documentados; ferramenta e limiares serão definidos com as medições, sem criar novo serviço por antecipação. GIF do fluxo e roteiro de demonstração de cinco minutos só entram no README quando houver negociação executável.

Os quatro dashboards cobrirão fluxo de ordens, risco/reservas, matching/mercado e back office/liquidação. Falha de uma meta deve gerar evidência e ajuste, não mudança silenciosa do requisito. Compensações, falhas de consumidor e reinícios serão exercitados por harness de infraestrutura; planilhas continuarão usando apenas APIs.

## Manutenção documental

[PR #6](https://github.com/mclovin137/Prospero/pull/6), **integrado em 2026-10-17** (merge 091480a): comparação do PDF, C4, mapa no README e playbook com 60 temas/exemplos; busca por palavras com full-text PostgreSQL. A pedido do usuário, guias CI/memória/documentação foram incorporados em rules e pastas auxiliares removidas após preservar P06 e reuso nos documentos vigentes. Sem funcionalidade nova nem mudança de estado das RFs.

Rodada complementar prévia: brief comum de redução documental sem perder RF/RNF, contratos, segurança e operação; cinco agentes medium separados (arquitetura, QA, segurança, operação e viabilidade) recomendaram seguir com ressalvas. Critérios: migrar conteúdo ativo antes de excluir, manter histórico fixado, atualizar skills/hashes/links e conferir os 60 exemplos. Relatos/execuções finais ficam no PR e síntese na memória, sem outro arquivo de relatório. Documentação histórica, capturas e pareceres continuam no [Git antes da consolidação](https://github.com/mclovin137/Prospero/tree/bd707fa972aa36c6811fa00d13ad73c3073721c1/docs).

[PR #7](https://github.com/mclovin137/Prospero/pull/7), **integrado em 2026-10-17 por autorização explícita do usuário** (merge 1c56351) — revisão visual do README: quatro recortes substituem C1/C2/C3/mapa antigos — serviços/bancos, Kafka/recuperação, SAGA/consistência e observabilidade. Nenhum tópico/grupo/consumer financeiro ou garantia de resiliência foi implementado. Fontes/estado conferidos e cinco perspectivas medium consultadas previamente (perfil C4/arquitetura, QA, segurança, operação e viabilidade), com ressalvas sobre atual/planejado, atomicidade local, limites de nó único e métricas futuras. Quatro fontes renderizadas e inspecionadas; revisão, QA e segurança independentes medium sem pendências. Comandos, resultados e limites registrados no PR, sem execução de falhas reais. Complemento solicitado: escopo/exclusões e explicações técnicas/de domínio no README, com dois modelos didáticos autorais inspirados nas capturas do usuário; seis pares de diagramas no total. Reuso, políticas de reserva e estratégia de testes preservam suas pendências, sem adotar a stack ou contratos das referências.

[PR #8](https://github.com/mclovin137/Prospero/pull/8), **integrado em 2026-10-17 por autorização explícita do usuário** (merge 9dcdad0): ajuste editorial solicitado para concentrar o README no Próspero, removendo seção de referência externa. Conteúdo técnico, diagramas e registros específicos de proveniência preservados; verificações documentais e três pareceres independentes sem achados.

Consolidação autorizada em 2026-10-17: PRs #9–#12 integrados, incluindo o conteúdo do #13. Todas as branches locais e remotas foram conferidas por ancestralidade; a `master` local acompanha a `main` remota. Nova QA da composição: runner 49/49, Angular 5/5, build, formatação, exemplo XLSX 4 cenários/8 passos e verificadores documentais/estrutura aprovados. Revisão e segurança independentes sem bloqueios; E2E, Maven e scanners não reexecutados nesta consolidação. Checks GitHub do PR de consolidação foram criados e estão em fila, sem execução comprovada; a CI depende de runner dedicado. P06 e RFs permanecem planejadas.

## Aceite comum às tarefas técnicas

Toda tarefa deve atualizar contratos, regras afetadas, inventário, roadmap e memória; executar testes proporcionais e a planilha quando elegível pelo ADR-004; documentar motivos, trade-offs, limitações e resultados no PR. Não substituir dependências futuras por comportamento de sucesso fictício. Evidências visuais entram quando houver alteração de interface.
