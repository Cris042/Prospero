# Próspero

Simulador local de mercado financeiro com cinco ações fictícias, livro de ofertas, risco, execução, posições e liquidação simulada em D+2. Projeto de estudo, sem serviços pagos ou integração com bolsas reais.

## Estado atual

A fundação contém quatro microsserviços Quarkus/Java 26, Angular e Docker Compose. Cada serviço expõe identidade técnica, saúde, OpenAPI e métricas. O painel inicial consulta identidade e prontidão reais. **Negociação, risco e liquidação ainda não foram implementados.** A evidência de execução desta etapa ficará no [resumo P02](docs/entregas/P02.md).

## Subir o ambiente

Pré-requisitos: Docker Engine com Compose v2 e Python 3. A máquina deve comportar os containers e a compilação; builds sequenciais reduzem o pico de memória. JDK e Node são instalados nas imagens, sem precisar alterar o Java do host.

```bash
python3 scripts/preparar-ambiente.py
docker compose config --quiet
COMPOSE_PARALLEL_LIMIT=1 docker compose build
docker compose up -d --wait --wait-timeout 240
python3 scripts/verificar-ambiente.py
```

O primeiro comando cria `.env` com credenciais aleatórias sem sobrescrever arquivo existente. As portas são exclusivas deste projeto e ficam publicadas somente em `127.0.0.1`. Nenhum banco, Kafka ou Redis é exposto ao host. Os nomes técnicos usam `prospero`, sem acento.

| Componente | Endereço local |
| --- | --- |
| Angular | http://localhost:4200 |
| Front office | http://localhost:8081/q/swagger-ui/ |
| Middle office | http://localhost:8082/q/swagger-ui/ |
| Matching engine | http://localhost:8083/q/swagger-ui/ |
| Back office | http://localhost:8084/q/swagger-ui/ |
| Grafana | http://localhost:13000 |
| Prometheus | http://localhost:19090 |
| Jaeger | http://localhost:16687 |
| Loki — prontidão | http://localhost:13100/ready |
| OpenTelemetry Collector — saúde | http://localhost:14333 |

Grafana: usuário `admin`, senha em `GRAFANA_ADMIN_PASSWORD` no `.env` local. Há um dashboard provisionado de fundação e fontes Prometheus/Loki/Jaeger. Os quatro dashboards financeiros serão entregues na V01. Jaeger usa memória nesta etapa; seus traces se perdem ao reiniciar. Demais dados de infraestrutura usam volumes nomeados do projeto.

## Desenvolvimento e testes

Com **JDK 26**, cada microsserviço funciona sem depender do POM da raiz:

```bash
cd front-office
./mvnw verify
```

Na raiz, `./mvnw verify` agrega os quatro builds. Com RAM limitada e outros projetos ativos, prefira executar cada serviço separadamente: o reator pode acumular memória entre módulos. Os testes de contrato usam HTTP com banco/Kafka desativados apenas no perfil de testes; a verificação Compose testa as dependências reais. Se o host tiver outra versão de Java, execute pelo container:

```bash
docker run --rm --memory=1g --cpus=2 \
  --user "$(id -u):$(id -g)" \
  -e MAVEN_USER_HOME=/tmp/maven \
  -e MAVEN_OPTS="-Xmx384m -XX:ActiveProcessorCount=2 -Duser.home=/tmp/prospero" \
  -v "$PWD:/workspace" -w /workspace/front-office \
  eclipse-temurin:26.0.2.1_1-jdk-noble sh ./mvnw -B -ntp verify
```

Repita o comando com o diretório de cada microsserviço. O exemplo mantém o cache Maven temporário dentro do container; um cache privado montado pode evitar novos downloads.

Angular requer Node 24.16.0 ou outra versão compatível com Angular 22.2.3:

```bash
cd frontend
npm ci
npm test -- --watch=false
npm run format:check
npm run build
npm start
```

`npm start` usa proxy para as APIs locais nas portas 8081–8084. A validação estática complementar roda com `python3 scripts/verificar-estrutura.py` na raiz. O runner de planilhas permanece na P03. O ai-memory já está instalado; veja [configuração e ativação dos hooks](docs/memoria.md).

## Operação local

```bash
docker compose ps
docker compose logs --tail=80 front-office
docker compose stop
docker compose start
```

`stop` preserva containers e dados. `docker compose down` remove containers/redes, preservando volumes nomeados. Não use `down -v` para uma parada normal: apaga os dados locais. Para subir só um microsserviço com seu banco e Kafka, use `docker compose up -d --build front-office`.

## Documentação e roadmaps

- [PRD de produto](docs/prds/PRD-001-simulador-prospero.md) e [HLD de arquitetura](docs/hlds/HLD-001-arquitetura-prospero.md).
- [Guia PRD/HLD/ADR](docs/documentacao.md) e [playbook único de desenvolvimento](docs/playbook-desenvolvimento.md).
- [Regras canônicas](docs/rules.md), [ADRs](docs/adrs/README.md), [inventário](docs/libs.md) e [catálogo de skills](docs/skills/README.md).
- [Perfis de agentes](docs/agentes/README.md), incluindo o gerador C4.
- [Roadmap global](docs/roadmap.md): uma tarefa principal por requisito funcional.
- Roadmaps: [FO](front-office/ROADMAP.md), [MO](middle-office/ROADMAP.md), [matching](matching-engine/ROADMAP.md), [BO](back-office/ROADMAP.md) e [Angular](frontend/ROADMAP.md).
- [HLDs por microsserviço](docs/hlds/README.md) e [índice de estrutura](#índice-de-estrutura).

Cada tarefa exige PR com alterações, motivos, trade-offs e validação. Repositório: [mclovin137/Prospero](https://github.com/mclovin137/Prospero). Veja [CI e runner dedicado](docs/ci.md); resumos locais não equivalem a PR aberto.

## Índice de estrutura

Mapa dos arquivos mantidos no repositório nesta entrega. Cada entrada descreve sua finalidade; arquivos com a mesma função aparecem em cada serviço porque os builds são independentes. Atualize o índice quando adicionar, mover ou remover arquivos. PRD explica produto; HLDs explicam arquitetura; ADRs registram decisões.

### Pastas

| Caminho | Motivo |
| --- | --- |
| `.agents/` | Recursos portáteis para agentes. |
| `.agents/skills/` | Skills locais, adaptadas e inventariadas. |
| `.agents/skills/criar-migration/` | Pacote da skill criar-migration. |
| `.agents/skills/deep-research/` | Pacote da skill deep-research. |
| `.agents/skills/gerar-adr/` | Pacote da skill gerar-adr. |
| `.agents/skills/gerar-hld/` | Pacote da skill gerar-hld. |
| `.agents/skills/gerar-prd/` | Pacote da skill gerar-prd. |
| `.agents/skills/humanizer/` | Pacote da skill humanizer. |
| `.agents/skills/humanizer/.claude-plugin/` | Recursos de referência do pacote humanizer, para consulta seletiva. |
| `.agents/skills/humanizer/.github/` | Recursos de referência do pacote humanizer, para consulta seletiva. |
| `.agents/skills/humanizer/.github/workflows/` | Recursos de referência do pacote humanizer, para consulta seletiva. |
| `.agents/skills/humanizer/agents/` | Recursos de referência do pacote humanizer, para consulta seletiva. |
| `.agents/skills/humanizer/scripts/` | Recursos de referência do pacote humanizer, para consulta seletiva. |
| `.agents/skills/iss-audit/` | Pacote da skill iss-audit. |
| `.agents/skills/iss-audit/agents/` | Recursos de referência do pacote iss-audit, para consulta seletiva. |
| `.agents/skills/loop-engineering/` | Pacote da skill loop-engineering. |
| `.agents/skills/pr-audit/` | Pacote da skill pr-audit. |
| `.agents/skills/pr-audit/agents/` | Recursos de referência do pacote pr-audit, para consulta seletiva. |
| `.agents/skills/refinar-task/` | Pacote da skill refinar-task. |
| `.agents/skills/release/` | Pacote da skill release. |
| `.agents/skills/security-audit/` | Pacote da skill security-audit. |
| `.agents/skills/security-audit/agents/` | Recursos de referência do pacote security-audit, para consulta seletiva. |
| `.agents/skills/security-audit/references/` | Recursos de referência do pacote security-audit, para consulta seletiva. |
| `.agents/skills/security-audit/scripts/` | Recursos de referência do pacote security-audit, para consulta seletiva. |
| `.agents/skills/subir-sistema/` | Pacote da skill subir-sistema. |
| `.agents/skills/tdd-workflow/` | Pacote da skill tdd-workflow. |
| `.agents/skills/verification-planning/` | Pacote da skill verification-planning. |
| `.agents/skills/worktrees/` | Pacote da skill worktrees. |
| `.codex/` | Adaptadores do Codex por projeto. |
| `.codex/agents/` | Configuração dos papéis de agentes. |
| `.github/` | Template de PR e automação GitHub. |
| `.github/workflows/` | Gates locais de revisão, testes e segurança. |
| `.mvn/` | Configuração do Maven Wrapper reproduzível. |
| `.mvn/wrapper/` | Configuração do Maven Wrapper reproduzível. |
| `back-office/` | Back office, pós-negociação, com build e roadmap próprios. |
| `back-office/.mvn/` | Configuração do Maven Wrapper reproduzível. |
| `back-office/.mvn/wrapper/` | Configuração do Maven Wrapper reproduzível. |
| `back-office/src/` | Código fonte mantido do componente. |
| `back-office/src/main/` | Código e recursos da aplicação. |
| `back-office/src/main/java/` | Raiz dos pacotes Java. |
| `back-office/src/main/java/br/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `back-office/src/main/java/br/prospero/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `back-office/src/main/java/br/prospero/bo/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `back-office/src/main/java/br/prospero/bo/sistema/` | Funcionalidade de identidade técnica. |
| `back-office/src/main/java/br/prospero/bo/sistema/actor/` | Coordena casos de uso. |
| `back-office/src/main/java/br/prospero/bo/sistema/dominio/` | Invariantes e valores puros. |
| `back-office/src/main/java/br/prospero/bo/sistema/dto/` | Contratos HTTP. |
| `back-office/src/main/java/br/prospero/bo/sistema/helper/` | Apoio puro, sem estado e sem I/O. |
| `back-office/src/main/java/br/prospero/bo/sistema/jpa/` | Fronteira de persistência. |
| `back-office/src/main/java/br/prospero/bo/sistema/rest/` | Entrada HTTP e mapeamento de contrato. |
| `back-office/src/main/resources/` | Configuração e recursos de runtime. |
| `back-office/src/main/resources/db/` | Recursos de persistência exclusivos do serviço. |
| `back-office/src/main/resources/db/migration/` | Migrações SQL versionadas Flyway. |
| `back-office/src/test/` | Testes do componente. |
| `back-office/src/test/java/` | Raiz dos pacotes Java. |
| `back-office/src/test/java/br/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `back-office/src/test/java/br/prospero/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `back-office/src/test/java/br/prospero/bo/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `back-office/src/test/java/br/prospero/bo/sistema/` | Funcionalidade de identidade técnica. |
| `docs/` | Documentação canônica e registros de entrega. |
| `docs/adrs/` | Histórico de decisões arquiteturais. |
| `docs/agentes/` | Definições portáteis de agentes. |
| `docs/entregas/` | Resumos históricos das tarefas. |
| `docs/evidencias/` | Resultados e limitações das verificações. |
| `docs/hlds/` | Desenho do ecossistema e dos quatro MS. |
| `docs/pesquisas/` | Análises de referências. |
| `docs/planos/` | Escopos técnicos futuros ainda não implementados. |
| `docs/prds/` | Produto, requisitos e critérios de aceite. |
| `docs/refinamentos/` | Critérios e pareceres anteriores à implementação. |
| `docs/skills/` | Catálogo e proveniência das skills. |
| `front-office/` | Front office, entrada e coordenação de ordens, com build e roadmap próprios. |
| `front-office/.mvn/` | Configuração do Maven Wrapper reproduzível. |
| `front-office/.mvn/wrapper/` | Configuração do Maven Wrapper reproduzível. |
| `front-office/src/` | Código fonte mantido do componente. |
| `front-office/src/main/` | Código e recursos da aplicação. |
| `front-office/src/main/java/` | Raiz dos pacotes Java. |
| `front-office/src/main/java/br/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `front-office/src/main/java/br/prospero/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `front-office/src/main/java/br/prospero/fo/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `front-office/src/main/java/br/prospero/fo/sistema/` | Funcionalidade de identidade técnica. |
| `front-office/src/main/java/br/prospero/fo/sistema/actor/` | Coordena casos de uso. |
| `front-office/src/main/java/br/prospero/fo/sistema/dominio/` | Invariantes e valores puros. |
| `front-office/src/main/java/br/prospero/fo/sistema/dto/` | Contratos HTTP. |
| `front-office/src/main/java/br/prospero/fo/sistema/helper/` | Apoio puro, sem estado e sem I/O. |
| `front-office/src/main/java/br/prospero/fo/sistema/jpa/` | Fronteira de persistência. |
| `front-office/src/main/java/br/prospero/fo/sistema/rest/` | Entrada HTTP e mapeamento de contrato. |
| `front-office/src/main/resources/` | Configuração e recursos de runtime. |
| `front-office/src/main/resources/db/` | Recursos de persistência exclusivos do serviço. |
| `front-office/src/main/resources/db/migration/` | Migrações SQL versionadas Flyway. |
| `front-office/src/test/` | Testes do componente. |
| `front-office/src/test/java/` | Raiz dos pacotes Java. |
| `front-office/src/test/java/br/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `front-office/src/test/java/br/prospero/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `front-office/src/test/java/br/prospero/fo/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `front-office/src/test/java/br/prospero/fo/sistema/` | Funcionalidade de identidade técnica. |
| `frontend/` | Interface Angular e futuro runner XLSX, com build e roadmap próprios. |
| `frontend/e2e/` | Recursos Angular: e2e. |
| `frontend/public/` | Recursos Angular: public. |
| `frontend/src/` | Código fonte mantido do componente. |
| `frontend/src/app/` | Recursos Angular: src / app. |
| `frontend/src/app/sistema/` | Funcionalidade de identidade técnica. |
| `infra/` | Configurações dos componentes de infraestrutura. |
| `infra/grafana/` | Configuração de grafana. |
| `infra/grafana/dashboards/` | Configuração de grafana / dashboards. |
| `infra/grafana/provisioning/` | Configuração de grafana / provisioning. |
| `infra/grafana/provisioning/dashboards/` | Configuração de grafana / provisioning / dashboards. |
| `infra/grafana/provisioning/datasources/` | Configuração de grafana / provisioning / datasources. |
| `infra/loki/` | Configuração de loki. |
| `infra/memoria/` | Configuração de memoria. |
| `infra/otel/` | Configuração de otel. |
| `infra/prometheus/` | Configuração de prometheus. |
| `matching-engine/` | Matching engine, execução determinística, com build e roadmap próprios. |
| `matching-engine/.mvn/` | Configuração do Maven Wrapper reproduzível. |
| `matching-engine/.mvn/wrapper/` | Configuração do Maven Wrapper reproduzível. |
| `matching-engine/src/` | Código fonte mantido do componente. |
| `matching-engine/src/main/` | Código e recursos da aplicação. |
| `matching-engine/src/main/java/` | Raiz dos pacotes Java. |
| `matching-engine/src/main/java/br/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `matching-engine/src/main/java/br/prospero/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `matching-engine/src/main/java/br/prospero/me/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `matching-engine/src/main/java/br/prospero/me/sistema/` | Funcionalidade de identidade técnica. |
| `matching-engine/src/main/java/br/prospero/me/sistema/actor/` | Coordena casos de uso. |
| `matching-engine/src/main/java/br/prospero/me/sistema/dominio/` | Invariantes e valores puros. |
| `matching-engine/src/main/java/br/prospero/me/sistema/dto/` | Contratos HTTP. |
| `matching-engine/src/main/java/br/prospero/me/sistema/helper/` | Apoio puro, sem estado e sem I/O. |
| `matching-engine/src/main/java/br/prospero/me/sistema/jpa/` | Fronteira de persistência. |
| `matching-engine/src/main/java/br/prospero/me/sistema/rest/` | Entrada HTTP e mapeamento de contrato. |
| `matching-engine/src/main/resources/` | Configuração e recursos de runtime. |
| `matching-engine/src/main/resources/db/` | Recursos de persistência exclusivos do serviço. |
| `matching-engine/src/main/resources/db/migration/` | Migrações SQL versionadas Flyway. |
| `matching-engine/src/test/` | Testes do componente. |
| `matching-engine/src/test/java/` | Raiz dos pacotes Java. |
| `matching-engine/src/test/java/br/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `matching-engine/src/test/java/br/prospero/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `matching-engine/src/test/java/br/prospero/me/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `matching-engine/src/test/java/br/prospero/me/sistema/` | Funcionalidade de identidade técnica. |
| `middle-office/` | Middle office, validação e risco, com build e roadmap próprios. |
| `middle-office/.mvn/` | Configuração do Maven Wrapper reproduzível. |
| `middle-office/.mvn/wrapper/` | Configuração do Maven Wrapper reproduzível. |
| `middle-office/src/` | Código fonte mantido do componente. |
| `middle-office/src/main/` | Código e recursos da aplicação. |
| `middle-office/src/main/java/` | Raiz dos pacotes Java. |
| `middle-office/src/main/java/br/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `middle-office/src/main/java/br/prospero/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `middle-office/src/main/java/br/prospero/mo/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `middle-office/src/main/java/br/prospero/mo/decimal/` | Conversão/representação numérica técnica do MO. |
| `middle-office/src/main/java/br/prospero/mo/decimal/dominio/` | Invariantes e valores puros. |
| `middle-office/src/main/java/br/prospero/mo/decimal/helper/` | Apoio puro, sem estado e sem I/O. |
| `middle-office/src/main/java/br/prospero/mo/sistema/` | Funcionalidade de identidade técnica. |
| `middle-office/src/main/java/br/prospero/mo/sistema/actor/` | Coordena casos de uso. |
| `middle-office/src/main/java/br/prospero/mo/sistema/dominio/` | Invariantes e valores puros. |
| `middle-office/src/main/java/br/prospero/mo/sistema/dto/` | Contratos HTTP. |
| `middle-office/src/main/java/br/prospero/mo/sistema/helper/` | Apoio puro, sem estado e sem I/O. |
| `middle-office/src/main/java/br/prospero/mo/sistema/jpa/` | Fronteira de persistência. |
| `middle-office/src/main/java/br/prospero/mo/sistema/rest/` | Entrada HTTP e mapeamento de contrato. |
| `middle-office/src/main/resources/` | Configuração e recursos de runtime. |
| `middle-office/src/main/resources/db/` | Recursos de persistência exclusivos do serviço. |
| `middle-office/src/main/resources/db/migration/` | Migrações SQL versionadas Flyway. |
| `middle-office/src/test/` | Testes do componente. |
| `middle-office/src/test/java/` | Raiz dos pacotes Java. |
| `middle-office/src/test/java/br/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `middle-office/src/test/java/br/prospero/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `middle-office/src/test/java/br/prospero/mo/` | Segmento do namespace Java br.prospero e seu bounded context. |
| `middle-office/src/test/java/br/prospero/mo/decimal/` | Conversão/representação numérica técnica do MO. |
| `middle-office/src/test/java/br/prospero/mo/sistema/` | Funcionalidade de identidade técnica. |
| `scripts/` | Automação de operação e verificações. |

### Arquivos

#### Raiz e ferramentas

| Arquivo | Finalidade |
| --- | --- |
| [.ai-memory.toml](.ai-memory.toml) | Identifica workspace/projeto da memória; não contém credenciais. |
| [.codex/agents/c4-diagram-generator.toml](.codex/agents/c4-diagram-generator.toml) | Configuração medium e instruções do papel C4. |
| [.codex/config.toml](.codex/config.toml) | Registra o papel C4 local no Codex compatível. |
| [.env.example](.env.example) | Modelo público de variáveis locais; não é a configuração privada. |
| [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md) | Campos de alteração, motivo, trade-offs e evidências do PR. |
| [.github/workflows/revisao.yml](.github/workflows/revisao.yml) | Gate de revisão determinística e comentário em pt-BR. |
| [.github/workflows/seguranca.yml](.github/workflows/seguranca.yml) | Gate de scanners de segredos/dependências e relatório. |
| [.github/workflows/testes.yml](.github/workflows/testes.yml) | Gate de testes em runner próprio com ambiente isolado. |
| [.gitignore](.gitignore) | Exclui segredos, caches, builds e arquivos locais do versionamento. |
| [.gitleaks.toml](.gitleaks.toml) | Configuração da detecção de segredos na CI. |
| [.mvn/wrapper/maven-wrapper.properties](.mvn/wrapper/maven-wrapper.properties) | Fixa distribuição Maven e checksum do Wrapper. |
| [AGENTS.md](AGENTS.md) | Orientações de contribuição e entrada para agentes. |
| [README.md](README.md) | Entrada do projeto, comandos e índice de cada pasta/arquivo mantido. |
| [docker-compose.ci.yml](docker-compose.ci.yml) | Sobrescreve a stack para isolamento de testes na CI. |
| [docker-compose.yml](docker-compose.yml) | Stack local: quatro MS/bancos, frontend, mensageria e telemetria. |
| [mvnw](mvnw) | Executa Maven Wrapper no Linux/macOS. |
| [mvnw.cmd](mvnw.cmd) | Executa Maven Wrapper no Windows. |
| [pom.xml](pom.xml) | Agregador Maven opcional dos quatro builds independentes. |

#### Skills locais

| Arquivo | Finalidade |
| --- | --- |
| [.agents/skills/README.md](.agents/skills/README.md) | Catálogo das skills locais. |
| [.agents/skills/criar-migration/SKILL.md](.agents/skills/criar-migration/SKILL.md) | Instruções locais e gatilhos da skill criar-migration. |
| [.agents/skills/criar-migration/UPSTREAM.md](.agents/skills/criar-migration/UPSTREAM.md) | Snapshot de referência fixado da skill criar-migration; não concede autoridade local. |
| [.agents/skills/deep-research/SKILL.md](.agents/skills/deep-research/SKILL.md) | Instruções locais e gatilhos da skill deep-research. |
| [.agents/skills/deep-research/UPSTREAM.md](.agents/skills/deep-research/UPSTREAM.md) | Snapshot de referência fixado da skill deep-research; não concede autoridade local. |
| [.agents/skills/gerar-adr/SKILL.md](.agents/skills/gerar-adr/SKILL.md) | Instruções locais e gatilhos da skill gerar-adr. |
| [.agents/skills/gerar-adr/UPSTREAM.md](.agents/skills/gerar-adr/UPSTREAM.md) | Snapshot de referência fixado da skill gerar-adr; não concede autoridade local. |
| [.agents/skills/gerar-hld/SKILL.md](.agents/skills/gerar-hld/SKILL.md) | Instruções locais e gatilhos da skill gerar-hld. |
| [.agents/skills/gerar-hld/UPSTREAM.md](.agents/skills/gerar-hld/UPSTREAM.md) | Snapshot de referência fixado da skill gerar-hld; não concede autoridade local. |
| [.agents/skills/gerar-prd/SKILL.md](.agents/skills/gerar-prd/SKILL.md) | Instruções locais e gatilhos da skill gerar-prd. |
| [.agents/skills/gerar-prd/UPSTREAM.md](.agents/skills/gerar-prd/UPSTREAM.md) | Snapshot de referência fixado da skill gerar-prd; não concede autoridade local. |
| [.agents/skills/humanizer/.claude-plugin/marketplace.json](.agents/skills/humanizer/.claude-plugin/marketplace.json) | Recurso do pacote humanizer (.claude-plugin); preservado para consulta, sem executar automaticamente. |
| [.agents/skills/humanizer/.claude-plugin/plugin.json](.agents/skills/humanizer/.claude-plugin/plugin.json) | Recurso do pacote humanizer (.claude-plugin); preservado para consulta, sem executar automaticamente. |
| [.agents/skills/humanizer/.github/workflows/validate.yml](.agents/skills/humanizer/.github/workflows/validate.yml) | Recurso do pacote humanizer (workflows); preservado para consulta, sem executar automaticamente. |
| [.agents/skills/humanizer/AGENTS.md](.agents/skills/humanizer/AGENTS.md) | Recurso do pacote humanizer (humanizer); preservado para consulta, sem executar automaticamente. |
| [.agents/skills/humanizer/LICENSE](.agents/skills/humanizer/LICENSE) | Licença distribuída com o pacote original humanizer. |
| [.agents/skills/humanizer/README.md](.agents/skills/humanizer/README.md) | Referência do pacote original humanizer, preservada com proveniência. |
| [.agents/skills/humanizer/SKILL.md](.agents/skills/humanizer/SKILL.md) | Instruções locais e gatilhos da skill humanizer. |
| [.agents/skills/humanizer/UPSTREAM.md](.agents/skills/humanizer/UPSTREAM.md) | Snapshot de referência fixado da skill humanizer; não concede autoridade local. |
| [.agents/skills/humanizer/agents/openai.yaml](.agents/skills/humanizer/agents/openai.yaml) | Recurso do pacote humanizer (agents); preservado para consulta, sem executar automaticamente. |
| [.agents/skills/humanizer/scripts/validate-package.py](.agents/skills/humanizer/scripts/validate-package.py) | Recurso do pacote humanizer (scripts); preservado para consulta, sem executar automaticamente. |
| [.agents/skills/iss-audit/SKILL.md](.agents/skills/iss-audit/SKILL.md) | Instruções locais e gatilhos da skill iss-audit. |
| [.agents/skills/iss-audit/UPSTREAM.md](.agents/skills/iss-audit/UPSTREAM.md) | Snapshot de referência fixado da skill iss-audit; não concede autoridade local. |
| [.agents/skills/iss-audit/agents/openai.yaml](.agents/skills/iss-audit/agents/openai.yaml) | Recurso do pacote iss-audit (agents); preservado para consulta, sem executar automaticamente. |
| [.agents/skills/loop-engineering/SKILL.md](.agents/skills/loop-engineering/SKILL.md) | Instruções locais e gatilhos da skill loop-engineering. |
| [.agents/skills/loop-engineering/UPSTREAM.md](.agents/skills/loop-engineering/UPSTREAM.md) | Snapshot de referência fixado da skill loop-engineering; não concede autoridade local. |
| [.agents/skills/pr-audit/SKILL.md](.agents/skills/pr-audit/SKILL.md) | Instruções locais e gatilhos da skill pr-audit. |
| [.agents/skills/pr-audit/UPSTREAM.md](.agents/skills/pr-audit/UPSTREAM.md) | Snapshot de referência fixado da skill pr-audit; não concede autoridade local. |
| [.agents/skills/pr-audit/agents/openai.yaml](.agents/skills/pr-audit/agents/openai.yaml) | Recurso do pacote pr-audit (agents); preservado para consulta, sem executar automaticamente. |
| [.agents/skills/refinar-task/SKILL.md](.agents/skills/refinar-task/SKILL.md) | Instruções locais e gatilhos da skill refinar-task. |
| [.agents/skills/refinar-task/UPSTREAM.md](.agents/skills/refinar-task/UPSTREAM.md) | Snapshot de referência fixado da skill refinar-task; não concede autoridade local. |
| [.agents/skills/release/SKILL.md](.agents/skills/release/SKILL.md) | Instruções locais e gatilhos da skill release. |
| [.agents/skills/release/UPSTREAM.md](.agents/skills/release/UPSTREAM.md) | Snapshot de referência fixado da skill release; não concede autoridade local. |
| [.agents/skills/security-audit/SKILL.md](.agents/skills/security-audit/SKILL.md) | Instruções locais e gatilhos da skill security-audit. |
| [.agents/skills/security-audit/UPSTREAM.md](.agents/skills/security-audit/UPSTREAM.md) | Snapshot de referência fixado da skill security-audit; não concede autoridade local. |
| [.agents/skills/security-audit/agents/openai.yaml](.agents/skills/security-audit/agents/openai.yaml) | Recurso do pacote security-audit (agents); preservado para consulta, sem executar automaticamente. |
| [.agents/skills/security-audit/references/ecosystem-checks.md](.agents/skills/security-audit/references/ecosystem-checks.md) | Recurso do pacote security-audit (references); preservado para consulta, sem executar automaticamente. |
| [.agents/skills/security-audit/references/security-checklist.md](.agents/skills/security-audit/references/security-checklist.md) | Recurso do pacote security-audit (references); preservado para consulta, sem executar automaticamente. |
| [.agents/skills/security-audit/scripts/security-surface.sh](.agents/skills/security-audit/scripts/security-surface.sh) | Recurso do pacote security-audit (scripts); preservado para consulta, sem executar automaticamente. |
| [.agents/skills/subir-sistema/SKILL.md](.agents/skills/subir-sistema/SKILL.md) | Instruções locais e gatilhos da skill subir-sistema. |
| [.agents/skills/tdd-workflow/SKILL.md](.agents/skills/tdd-workflow/SKILL.md) | Instruções locais e gatilhos da skill tdd-workflow. |
| [.agents/skills/tdd-workflow/UPSTREAM.md](.agents/skills/tdd-workflow/UPSTREAM.md) | Snapshot de referência fixado da skill tdd-workflow; não concede autoridade local. |
| [.agents/skills/verification-planning/SKILL.md](.agents/skills/verification-planning/SKILL.md) | Instruções locais e gatilhos da skill verification-planning. |
| [.agents/skills/verification-planning/UPSTREAM.md](.agents/skills/verification-planning/UPSTREAM.md) | Snapshot de referência fixado da skill verification-planning; não concede autoridade local. |
| [.agents/skills/worktrees/SKILL.md](.agents/skills/worktrees/SKILL.md) | Instruções locais e gatilhos da skill worktrees. |
| [.agents/skills/worktrees/UPSTREAM.md](.agents/skills/worktrees/UPSTREAM.md) | Snapshot de referência fixado da skill worktrees; não concede autoridade local. |

#### Documentação

| Arquivo | Finalidade |
| --- | --- |
| [docs/adrs/ADR-001-arquitetura-backend.md](docs/adrs/ADR-001-arquitetura-backend.md) | Decisão arquitetural, contexto, alternativas e consequências: arquitetura backend. |
| [docs/adrs/ADR-002-padroes-dominio.md](docs/adrs/ADR-002-padroes-dominio.md) | Decisão arquitetural, contexto, alternativas e consequências: padroes dominio. |
| [docs/adrs/ADR-003-contratos-api.md](docs/adrs/ADR-003-contratos-api.md) | Decisão arquitetural, contexto, alternativas e consequências: contratos api. |
| [docs/adrs/ADR-004-testes-planilha.md](docs/adrs/ADR-004-testes-planilha.md) | Decisão arquitetural, contexto, alternativas e consequências: testes planilha. |
| [docs/adrs/ADR-005-versoes-fundacao.md](docs/adrs/ADR-005-versoes-fundacao.md) | Decisão arquitetural, contexto, alternativas e consequências: versoes fundacao. |
| [docs/adrs/README.md](docs/adrs/README.md) | Índice e responsabilidades de adrs. |
| [docs/agentes/README.md](docs/agentes/README.md) | Índice e responsabilidades de agentes. |
| [docs/agentes/c4-diagram-generator.md](docs/agentes/c4-diagram-generator.md) | Perfil portátil de propostas C4 com fontes, limites e nível medium. |
| [docs/ci.md](docs/ci.md) | Instalação/isolamento do runner e limites dos gates. |
| [docs/documentacao.md](docs/documentacao.md) | Quando usar PRD/HLD/ADR e como verificar aderência. |
| [docs/entregas/P01.md](docs/entregas/P01.md) | Resumo histórico da entrega P01 e suas evidências. |
| [docs/entregas/P02.md](docs/entregas/P02.md) | Resumo histórico da entrega P02 e suas evidências. |
| [docs/entregas/P04.md](docs/entregas/P04.md) | Resumo histórico da entrega P04 e suas evidências. |
| [docs/evidencias/P02-desktop.png](docs/evidencias/P02-desktop.png) | Captura da interface para evidência visual da P02. |
| [docs/evidencias/P02-mobile.png](docs/evidencias/P02-mobile.png) | Captura da interface para evidência visual da P02. |
| [docs/evidencias/P02-testes.md](docs/evidencias/P02-testes.md) | Registro de P02 testes com escopo, resultados e limites. |
| [docs/evidencias/P04-revisao.md](docs/evidencias/P04-revisao.md) | Registro de P04 revisao com escopo, resultados e limites. |
| [docs/evidencias/P04-seguranca.md](docs/evidencias/P04-seguranca.md) | Registro de P04 seguranca com escopo, resultados e limites. |
| [docs/evidencias/P04-testes.md](docs/evidencias/P04-testes.md) | Registro de P04 testes com escopo, resultados e limites. |
| [docs/evidencias/P05-revisao.md](docs/evidencias/P05-revisao.md) | Registro de P05 revisao com escopo, resultados e limites. |
| [docs/evidencias/P05-seguranca.md](docs/evidencias/P05-seguranca.md) | Registro de P05 seguranca com escopo, resultados e limites. |
| [docs/evidencias/P05-testes.md](docs/evidencias/P05-testes.md) | Registro de P05 testes com escopo, resultados e limites. |
| [docs/evidencias/P07-revisao.md](docs/evidencias/P07-revisao.md) | Registro de P07 revisao com escopo, resultados e limites. |
| [docs/evidencias/P07-seguranca.md](docs/evidencias/P07-seguranca.md) | Registro de P07 seguranca com escopo, resultados e limites. |
| [docs/evidencias/P07-testes.md](docs/evidencias/P07-testes.md) | Registro de P07 testes com escopo, resultados e limites. |
| [docs/evidencias/P08-aderencia.md](docs/evidencias/P08-aderencia.md) | Registro de P08 aderencia com escopo, resultados e limites. |
| [docs/evidencias/P08-revisao.md](docs/evidencias/P08-revisao.md) | Registro de P08 revisao com escopo, resultados e limites. |
| [docs/evidencias/P08-seguranca.md](docs/evidencias/P08-seguranca.md) | Registro de P08 seguranca com escopo, resultados e limites. |
| [docs/evidencias/P08-testes.md](docs/evidencias/P08-testes.md) | Registro de P08 testes com escopo, resultados e limites. |
| [docs/hlds/HLD-001-arquitetura-prospero.md](docs/hlds/HLD-001-arquitetura-prospero.md) | Desenho macro de ecossistema; distingue atual/planejado e pendências. |
| [docs/hlds/HLD-002-front-office.md](docs/hlds/HLD-002-front-office.md) | Desenho macro de front office; distingue atual/planejado e pendências. |
| [docs/hlds/HLD-003-middle-office.md](docs/hlds/HLD-003-middle-office.md) | Desenho macro de middle office; distingue atual/planejado e pendências. |
| [docs/hlds/HLD-004-matching-engine.md](docs/hlds/HLD-004-matching-engine.md) | Desenho macro de matching engine; distingue atual/planejado e pendências. |
| [docs/hlds/HLD-005-back-office.md](docs/hlds/HLD-005-back-office.md) | Desenho macro de back office; distingue atual/planejado e pendências. |
| [docs/hlds/README.md](docs/hlds/README.md) | Índice e responsabilidades de hlds. |
| [docs/libs.md](docs/libs.md) | Inventário de versões, finalidade, adoção e overrides. |
| [docs/memoria.md](docs/memoria.md) | Operação e limites da memória e ativação dos hooks. |
| [docs/pesquisas/ci-cd.md](docs/pesquisas/ci-cd.md) | Análise de referência e limites: ci cd. |
| [docs/pesquisas/reuso-minerva-financas.md](docs/pesquisas/reuso-minerva-financas.md) | Análise de referência e limites: reuso minerva financas. |
| [docs/planos/P06-carga-observabilidade.md](docs/planos/P06-carga-observabilidade.md) | Escopo futuro e critérios da API de carga/observabilidade, sem implementação. |
| [docs/playbook-desenvolvimento.md](docs/playbook-desenvolvimento.md) | Consulta seletiva de desenvolvimento e diagnóstico local. |
| [docs/prds/PRD-001-simulador-prospero.md](docs/prds/PRD-001-simulador-prospero.md) | Requisitos de produto, atores, escopo, RFs/RNFs e aceites do simulador. |
| [docs/refinamentos/P04-skills-ci.md](docs/refinamentos/P04-skills-ci.md) | Brief, pareceres independentes, decisões e critérios antes da tarefa P04. |
| [docs/refinamentos/P05-P07-reuso-c4.md](docs/refinamentos/P05-P07-reuso-c4.md) | Brief, pareceres independentes, decisões e critérios antes da tarefa P05. |
| [docs/refinamentos/P08-fluxo-documental.md](docs/refinamentos/P08-fluxo-documental.md) | Brief, pareceres independentes, decisões e critérios antes da tarefa P08. |
| [docs/roadmap.md](docs/roadmap.md) | Tarefas globais, dependências, aceites e estado dos PRs. |
| [docs/rules.md](docs/rules.md) | Contrato canônico de arquitetura e fluxo de contribuição. |
| [docs/skills/README.md](docs/skills/README.md) | Índice e responsabilidades de skills. |
| [docs/skills/origens.json](docs/skills/origens.json) | Commits de origem e SHA-256 de todos os arquivos das skills. |

#### Front office

| Arquivo | Finalidade |
| --- | --- |
| [front-office/.dockerignore](front-office/.dockerignore) | Evita enviar builds/caches/arquivos locais ao contexto Docker. |
| [front-office/.mvn/wrapper/maven-wrapper.properties](front-office/.mvn/wrapper/maven-wrapper.properties) | Fixa distribuição Maven e checksum do Wrapper. |
| [front-office/Dockerfile](front-office/Dockerfile) | Build e runtime em container do serviço Java26/Quarkus. |
| [front-office/ROADMAP.md](front-office/ROADMAP.md) | Participação do componente nas tarefas globais, sem duplicar PR por RF. |
| [front-office/mvnw](front-office/mvnw) | Wrapper Maven independente do serviço, para Linux/macOS. |
| [front-office/mvnw.cmd](front-office/mvnw.cmd) | Wrapper Maven independente do serviço, para Windows. |
| [front-office/pom.xml](front-office/pom.xml) | Build independente, BOM Quarkus, dependências e plugins do serviço. |
| [front-office/src/main/java/br/prospero/fo/sistema/actor/ConsultarSistemaActor.java](front-office/src/main/java/br/prospero/fo/sistema/actor/ConsultarSistemaActor.java) | Caso de uso técnico que consulta a identidade do serviço. |
| [front-office/src/main/java/br/prospero/fo/sistema/dominio/package-info.java](front-office/src/main/java/br/prospero/fo/sistema/dominio/package-info.java) | Marca o pacote dominio da funcionalidade, sem implementação financeira. |
| [front-office/src/main/java/br/prospero/fo/sistema/dto/SistemaResponseDTO.java](front-office/src/main/java/br/prospero/fo/sistema/dto/SistemaResponseDTO.java) | Contrato de resposta da identidade técnica. |
| [front-office/src/main/java/br/prospero/fo/sistema/helper/package-info.java](front-office/src/main/java/br/prospero/fo/sistema/helper/package-info.java) | Marca o pacote helper da funcionalidade, sem implementação financeira. |
| [front-office/src/main/java/br/prospero/fo/sistema/jpa/package-info.java](front-office/src/main/java/br/prospero/fo/sistema/jpa/package-info.java) | Marca o pacote jpa da funcionalidade, sem implementação financeira. |
| [front-office/src/main/java/br/prospero/fo/sistema/rest/SistemaResource.java](front-office/src/main/java/br/prospero/fo/sistema/rest/SistemaResource.java) | Endpoint REST GET /api/v1/sistema, com OpenAPI. |
| [front-office/src/main/resources/application.properties](front-office/src/main/resources/application.properties) | Configura HTTP, banco, Kafka, migrações, telemetria e perfil de teste. |
| [front-office/src/main/resources/db/migration/V1__identificar_schema.sql](front-office/src/main/resources/db/migration/V1__identificar_schema.sql) | Identifica o schema do serviço; não cria tabelas financeiras. |
| [front-office/src/test/java/br/prospero/fo/ArquiteturaTest.java](front-office/src/test/java/br/prospero/fo/ArquiteturaTest.java) | Testes ArchUnit de fronteiras REST/JPA, pureza e independência. |
| [front-office/src/test/java/br/prospero/fo/sistema/SistemaResourceTest.java](front-office/src/test/java/br/prospero/fo/sistema/SistemaResourceTest.java) | Testes HTTP de identidade e documentação do serviço. |

#### Middle office

| Arquivo | Finalidade |
| --- | --- |
| [middle-office/.dockerignore](middle-office/.dockerignore) | Evita enviar builds/caches/arquivos locais ao contexto Docker. |
| [middle-office/.mvn/wrapper/maven-wrapper.properties](middle-office/.mvn/wrapper/maven-wrapper.properties) | Fixa distribuição Maven e checksum do Wrapper. |
| [middle-office/Dockerfile](middle-office/Dockerfile) | Build e runtime em container do serviço Java26/Quarkus. |
| [middle-office/ROADMAP.md](middle-office/ROADMAP.md) | Participação do componente nas tarefas globais, sem duplicar PR por RF. |
| [middle-office/mvnw](middle-office/mvnw) | Wrapper Maven independente do serviço, para Linux/macOS. |
| [middle-office/mvnw.cmd](middle-office/mvnw.cmd) | Wrapper Maven independente do serviço, para Windows. |
| [middle-office/pom.xml](middle-office/pom.xml) | Build independente, BOM Quarkus, dependências e plugins do serviço. |
| [middle-office/src/main/java/br/prospero/mo/decimal/dominio/RegraDeSinal.java](middle-office/src/main/java/br/prospero/mo/decimal/dominio/RegraDeSinal.java) | Política explícita de sinal para conversão decimal. |
| [middle-office/src/main/java/br/prospero/mo/decimal/dominio/ValorEscalado.java](middle-office/src/main/java/br/prospero/mo/decimal/dominio/ValorEscalado.java) | Representação técnica imutável de unidades/escala, sem política financeira. |
| [middle-office/src/main/java/br/prospero/mo/decimal/helper/ConversaoDecimalExata.java](middle-office/src/main/java/br/prospero/mo/decimal/helper/ConversaoDecimalExata.java) | Conversão exata com limites de escala, precisão e overflow. |
| [middle-office/src/main/java/br/prospero/mo/sistema/actor/ConsultarSistemaActor.java](middle-office/src/main/java/br/prospero/mo/sistema/actor/ConsultarSistemaActor.java) | Caso de uso técnico que consulta a identidade do serviço. |
| [middle-office/src/main/java/br/prospero/mo/sistema/dominio/package-info.java](middle-office/src/main/java/br/prospero/mo/sistema/dominio/package-info.java) | Marca o pacote dominio da funcionalidade, sem implementação financeira. |
| [middle-office/src/main/java/br/prospero/mo/sistema/dto/SistemaResponseDTO.java](middle-office/src/main/java/br/prospero/mo/sistema/dto/SistemaResponseDTO.java) | Contrato de resposta da identidade técnica. |
| [middle-office/src/main/java/br/prospero/mo/sistema/helper/package-info.java](middle-office/src/main/java/br/prospero/mo/sistema/helper/package-info.java) | Marca o pacote helper da funcionalidade, sem implementação financeira. |
| [middle-office/src/main/java/br/prospero/mo/sistema/jpa/package-info.java](middle-office/src/main/java/br/prospero/mo/sistema/jpa/package-info.java) | Marca o pacote jpa da funcionalidade, sem implementação financeira. |
| [middle-office/src/main/java/br/prospero/mo/sistema/rest/SistemaResource.java](middle-office/src/main/java/br/prospero/mo/sistema/rest/SistemaResource.java) | Endpoint REST GET /api/v1/sistema, com OpenAPI. |
| [middle-office/src/main/resources/application.properties](middle-office/src/main/resources/application.properties) | Configura HTTP, banco, Kafka, migrações, telemetria e perfil de teste. |
| [middle-office/src/main/resources/db/migration/V1__identificar_schema.sql](middle-office/src/main/resources/db/migration/V1__identificar_schema.sql) | Identifica o schema do serviço; não cria tabelas financeiras. |
| [middle-office/src/test/java/br/prospero/mo/ArquiteturaTest.java](middle-office/src/test/java/br/prospero/mo/ArquiteturaTest.java) | Testes ArchUnit de fronteiras REST/JPA, pureza e independência. |
| [middle-office/src/test/java/br/prospero/mo/decimal/DecimalExatoTest.java](middle-office/src/test/java/br/prospero/mo/decimal/DecimalExatoTest.java) | Testes de limites, exatidão, sinal e representação decimal. |
| [middle-office/src/test/java/br/prospero/mo/sistema/SistemaResourceTest.java](middle-office/src/test/java/br/prospero/mo/sistema/SistemaResourceTest.java) | Testes HTTP de identidade e documentação do serviço. |

#### Matching engine

| Arquivo | Finalidade |
| --- | --- |
| [matching-engine/.dockerignore](matching-engine/.dockerignore) | Evita enviar builds/caches/arquivos locais ao contexto Docker. |
| [matching-engine/.mvn/wrapper/maven-wrapper.properties](matching-engine/.mvn/wrapper/maven-wrapper.properties) | Fixa distribuição Maven e checksum do Wrapper. |
| [matching-engine/Dockerfile](matching-engine/Dockerfile) | Build e runtime em container do serviço Java26/Quarkus. |
| [matching-engine/ROADMAP.md](matching-engine/ROADMAP.md) | Participação do componente nas tarefas globais, sem duplicar PR por RF. |
| [matching-engine/mvnw](matching-engine/mvnw) | Wrapper Maven independente do serviço, para Linux/macOS. |
| [matching-engine/mvnw.cmd](matching-engine/mvnw.cmd) | Wrapper Maven independente do serviço, para Windows. |
| [matching-engine/pom.xml](matching-engine/pom.xml) | Build independente, BOM Quarkus, dependências e plugins do serviço. |
| [matching-engine/src/main/java/br/prospero/me/sistema/actor/ConsultarSistemaActor.java](matching-engine/src/main/java/br/prospero/me/sistema/actor/ConsultarSistemaActor.java) | Caso de uso técnico que consulta a identidade do serviço. |
| [matching-engine/src/main/java/br/prospero/me/sistema/dominio/package-info.java](matching-engine/src/main/java/br/prospero/me/sistema/dominio/package-info.java) | Marca o pacote dominio da funcionalidade, sem implementação financeira. |
| [matching-engine/src/main/java/br/prospero/me/sistema/dto/SistemaResponseDTO.java](matching-engine/src/main/java/br/prospero/me/sistema/dto/SistemaResponseDTO.java) | Contrato de resposta da identidade técnica. |
| [matching-engine/src/main/java/br/prospero/me/sistema/helper/package-info.java](matching-engine/src/main/java/br/prospero/me/sistema/helper/package-info.java) | Marca o pacote helper da funcionalidade, sem implementação financeira. |
| [matching-engine/src/main/java/br/prospero/me/sistema/jpa/package-info.java](matching-engine/src/main/java/br/prospero/me/sistema/jpa/package-info.java) | Marca o pacote jpa da funcionalidade, sem implementação financeira. |
| [matching-engine/src/main/java/br/prospero/me/sistema/rest/SistemaResource.java](matching-engine/src/main/java/br/prospero/me/sistema/rest/SistemaResource.java) | Endpoint REST GET /api/v1/sistema, com OpenAPI. |
| [matching-engine/src/main/resources/application.properties](matching-engine/src/main/resources/application.properties) | Configura HTTP, banco, Kafka, migrações, telemetria e perfil de teste. |
| [matching-engine/src/main/resources/db/migration/V1__identificar_schema.sql](matching-engine/src/main/resources/db/migration/V1__identificar_schema.sql) | Identifica o schema do serviço; não cria tabelas financeiras. |
| [matching-engine/src/test/java/br/prospero/me/ArquiteturaTest.java](matching-engine/src/test/java/br/prospero/me/ArquiteturaTest.java) | Testes ArchUnit de fronteiras REST/JPA, pureza e independência. |
| [matching-engine/src/test/java/br/prospero/me/sistema/SistemaResourceTest.java](matching-engine/src/test/java/br/prospero/me/sistema/SistemaResourceTest.java) | Testes HTTP de identidade e documentação do serviço. |

#### Back office

| Arquivo | Finalidade |
| --- | --- |
| [back-office/.dockerignore](back-office/.dockerignore) | Evita enviar builds/caches/arquivos locais ao contexto Docker. |
| [back-office/.mvn/wrapper/maven-wrapper.properties](back-office/.mvn/wrapper/maven-wrapper.properties) | Fixa distribuição Maven e checksum do Wrapper. |
| [back-office/Dockerfile](back-office/Dockerfile) | Build e runtime em container do serviço Java26/Quarkus. |
| [back-office/ROADMAP.md](back-office/ROADMAP.md) | Participação do componente nas tarefas globais, sem duplicar PR por RF. |
| [back-office/mvnw](back-office/mvnw) | Wrapper Maven independente do serviço, para Linux/macOS. |
| [back-office/mvnw.cmd](back-office/mvnw.cmd) | Wrapper Maven independente do serviço, para Windows. |
| [back-office/pom.xml](back-office/pom.xml) | Build independente, BOM Quarkus, dependências e plugins do serviço. |
| [back-office/src/main/java/br/prospero/bo/sistema/actor/ConsultarSistemaActor.java](back-office/src/main/java/br/prospero/bo/sistema/actor/ConsultarSistemaActor.java) | Caso de uso técnico que consulta a identidade do serviço. |
| [back-office/src/main/java/br/prospero/bo/sistema/dominio/package-info.java](back-office/src/main/java/br/prospero/bo/sistema/dominio/package-info.java) | Marca o pacote dominio da funcionalidade, sem implementação financeira. |
| [back-office/src/main/java/br/prospero/bo/sistema/dto/SistemaResponseDTO.java](back-office/src/main/java/br/prospero/bo/sistema/dto/SistemaResponseDTO.java) | Contrato de resposta da identidade técnica. |
| [back-office/src/main/java/br/prospero/bo/sistema/helper/package-info.java](back-office/src/main/java/br/prospero/bo/sistema/helper/package-info.java) | Marca o pacote helper da funcionalidade, sem implementação financeira. |
| [back-office/src/main/java/br/prospero/bo/sistema/jpa/package-info.java](back-office/src/main/java/br/prospero/bo/sistema/jpa/package-info.java) | Marca o pacote jpa da funcionalidade, sem implementação financeira. |
| [back-office/src/main/java/br/prospero/bo/sistema/rest/SistemaResource.java](back-office/src/main/java/br/prospero/bo/sistema/rest/SistemaResource.java) | Endpoint REST GET /api/v1/sistema, com OpenAPI. |
| [back-office/src/main/resources/application.properties](back-office/src/main/resources/application.properties) | Configura HTTP, banco, Kafka, migrações, telemetria e perfil de teste. |
| [back-office/src/main/resources/db/migration/V1__identificar_schema.sql](back-office/src/main/resources/db/migration/V1__identificar_schema.sql) | Identifica o schema do serviço; não cria tabelas financeiras. |
| [back-office/src/test/java/br/prospero/bo/ArquiteturaTest.java](back-office/src/test/java/br/prospero/bo/ArquiteturaTest.java) | Testes ArchUnit de fronteiras REST/JPA, pureza e independência. |
| [back-office/src/test/java/br/prospero/bo/sistema/SistemaResourceTest.java](back-office/src/test/java/br/prospero/bo/sistema/SistemaResourceTest.java) | Testes HTTP de identidade e documentação do serviço. |

#### Angular

| Arquivo | Finalidade |
| --- | --- |
| [frontend/.dockerignore](frontend/.dockerignore) | Evita enviar builds/caches/arquivos locais ao contexto Docker. |
| [frontend/.editorconfig](frontend/.editorconfig) | Indentação, encoding e newline para editores do frontend. |
| [frontend/.gitignore](frontend/.gitignore) | Exclui saídas/caches locais do workspace Angular. |
| [frontend/.prettierrc](frontend/.prettierrc) | Convenção de formatação Prettier do frontend. |
| [frontend/Dockerfile](frontend/Dockerfile) | Build e runtime em container do Angular/Nginx. |
| [frontend/README.md](frontend/README.md) | Comandos e orientações específicas do frontend Angular. |
| [frontend/ROADMAP.md](frontend/ROADMAP.md) | Participação do componente nas tarefas globais, sem duplicar PR por RF. |
| [frontend/angular.json](frontend/angular.json) | Configura build, serve e testes do workspace Angular. |
| [frontend/e2e/fundacao.spec.ts](frontend/e2e/fundacao.spec.ts) | Teste de componente ou fluxo no navegador: fundacao.spec.ts. |
| [frontend/nginx.conf](frontend/nginx.conf) | Servidor estático e proxy das APIs técnicas no container. |
| [frontend/package-lock.json](frontend/package-lock.json) | Resolução exata das dependências npm. |
| [frontend/package.json](frontend/package.json) | Dependências e comandos de desenvolvimento/testes Angular. |
| [frontend/playwright.config.ts](frontend/playwright.config.ts) | Configuração dos testes E2E no navegador. |
| [frontend/proxy.conf.json](frontend/proxy.conf.json) | Proxy das APIs no desenvolvimento Angular. |
| [frontend/public/favicon.ico](frontend/public/favicon.ico) | Ícone estático da aplicação no navegador. |
| [frontend/src/app/app.config.ts](frontend/src/app/app.config.ts) | Componente/configuração Angular: app.config.ts. |
| [frontend/src/app/app.css](frontend/src/app/app.css) | Estilos do componente Angular. |
| [frontend/src/app/app.html](frontend/src/app/app.html) | Template Angular da interface técnica. |
| [frontend/src/app/app.routes.ts](frontend/src/app/app.routes.ts) | Componente/configuração Angular: app.routes.ts. |
| [frontend/src/app/app.spec.ts](frontend/src/app/app.spec.ts) | Teste de componente ou fluxo no navegador: app.spec.ts. |
| [frontend/src/app/app.ts](frontend/src/app/app.ts) | Componente/configuração Angular: app.ts. |
| [frontend/src/app/sistema/sistema.service.spec.ts](frontend/src/app/sistema/sistema.service.spec.ts) | Teste de componente ou fluxo no navegador: sistema.service.spec.ts. |
| [frontend/src/app/sistema/sistema.service.ts](frontend/src/app/sistema/sistema.service.ts) | Componente/configuração Angular: sistema.service.ts. |
| [frontend/src/index.html](frontend/src/index.html) | Documento HTML que hospeda a aplicação. |
| [frontend/src/main.ts](frontend/src/main.ts) | Inicializa a aplicação Angular. |
| [frontend/src/styles.css](frontend/src/styles.css) | Estilos globais da interface. |
| [frontend/tsconfig.app.json](frontend/tsconfig.app.json) | Compilação TypeScript da aplicação. |
| [frontend/tsconfig.json](frontend/tsconfig.json) | Opções TypeScript comuns ao frontend. |
| [frontend/tsconfig.spec.json](frontend/tsconfig.spec.json) | Compilação TypeScript dos testes. |

#### Infraestrutura

| Arquivo | Finalidade |
| --- | --- |
| [infra/grafana/dashboards/fundacao.json](infra/grafana/dashboards/fundacao.json) | Dashboard provisionado da fundação técnica. |
| [infra/grafana/provisioning/dashboards/painel.yaml](infra/grafana/provisioning/dashboards/painel.yaml) | Registra a pasta de dashboards no Grafana. |
| [infra/grafana/provisioning/datasources/fontes.yaml](infra/grafana/provisioning/datasources/fontes.yaml) | Provisiona Prometheus, Loki e Jaeger como fontes Grafana. |
| [infra/loki/config.yaml](infra/loki/config.yaml) | Armazenamento e limites locais dos logs Loki. |
| [infra/memoria/compose.yml](infra/memoria/compose.yml) | Servidor ai-memory separado, com persistência privada local. |
| [infra/otel/collector.yaml](infra/otel/collector.yaml) | Recebe/exporta sinais de telemetria da aplicação. |
| [infra/prometheus/prometheus.yml](infra/prometheus/prometheus.yml) | Coleta métricas dos quatro serviços Quarkus. |

#### Scripts

| Arquivo | Finalidade |
| --- | --- |
| [scripts/ci-preflight.py](scripts/ci-preflight.py) | Verifica isolamento e condições prévias da CI. |
| [scripts/ci-revisao.sh](scripts/ci-revisao.sh) | Executa as verificações determinísticas do gate de revisão. |
| [scripts/ci-seguranca.sh](scripts/ci-seguranca.sh) | Executa scanners e verifica cobertura dos artefatos Java. |
| [scripts/ci-testes.sh](scripts/ci-testes.sh) | Coordena build/testes e limpeza restrita do ambiente CI. |
| [scripts/memoria.sh](scripts/memoria.sh) | Acessa o CLI de memória com configuração local do projeto. |
| [scripts/preparar-ambiente.py](scripts/preparar-ambiente.py) | Gera configuração local e segredos fora do Git. |
| [scripts/verificar-ambiente.py](scripts/verificar-ambiente.py) | Smoke test de saúde, rotas e telemetria da stack. |
| [scripts/verificar-documentacao.py](scripts/verificar-documentacao.py) | Confere links locais e proveniência/hashes das skills. |
| [scripts/verificar-estrutura.py](scripts/verificar-estrutura.py) | Confere fronteiras e pureza dos serviços no código fonte. |
| [scripts/verificar-scan-java.py](scripts/verificar-scan-java.py) | Confere se o scanner realmente cobriu componentes Java. |

### Arquivos locais e gerados

`.env` contém configuração/segredos locais e não entra no Git. `target/` contém classes, relatórios e pacotes Maven; `frontend/node_modules/` guarda dependências instaladas; `frontend/dist/`, relatórios Playwright, caches e `.slim/` são saídas locais. `.git/` mantém o histórico. Volumes Docker e os dados privados do ai-memory ficam fora deste inventário de fontes. Esses conteúdos variam por máquina e não devem ser tratados como arquivos de implementação mantidos.
