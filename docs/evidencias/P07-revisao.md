# P07 — revisão independente

Data: 2026-10-17. Agente exclusivo de revisão, esforço medium, separado do implementador, QA e segurança. Aplicada a skill local `pr-audit` e seu upstream preservado. Revisão estática da branch `task/p07-agente-c4`, base local `a694cd5`; nenhum arquivo de implementação foi alterado pelo revisor.

## Resultado

**Sem achados bloqueantes na inspeção estática.** Perfil portátil, adaptador e integração documental respeitam o [refinamento P05/P07](../refinamentos/P05-P07-reuso-c4.md). Encaminhamento à decisão do usuário depende das evidências independentes de QA e segurança. Esta revisão não comprova que o papel foi descoberto nativamente na sessão e não autoriza merge.

## Escopo e evidência

Lidos integralmente `docs/agentes/README.md`, `docs/agentes/c4-diagram-generator.md`, `.codex/config.toml` e `.codex/agents/c4-diagram-generator.toml`; revisados os diffs de `AGENTS.md`, `README.md` e `docs/rules.md`, além das fontes canônicas/refinamento já recuperadas nesta sessão. O perfil externo fixado em `76a991d7296cddba5e595a07e9b3b6f8da581b78` foi comparado com sua adaptação local. Não se executaram ferramentas ou código do doador.

Comandos: `git status --short`, `git branch --show-current`, `git log -3 --oneline`, `git diff --stat`, `git diff -- AGENTS.md docs/rules.md README.md`, `git diff --check`, leitura integral dos arquivos, parsing TOML com `python3/tomllib` e hashes SHA-256. `git diff --check` terminou sem saída; os novos arquivos não rastreados também foram lidos. Nenhum build, teste de aplicação, renderizador ou chamada de modelo foi executado pelo revisor.

| Critério | Constatação |
| --- | --- |
| Fontes locais substituem FDD obrigatório | Perfil aceita PRD/HLD quando existentes, PROJETO, ADRs, manifests e código pertinentes; exige confrontar fontes e implementação |
| Evidência por nível C4 | C1–C4 têm requisitos de suficiência; níveis sem fonte são omitidos com justificativa |
| Planejado versus implementado | Perfil proíbe representar SAGA/outbox/negociação como operacionais sem evidência e exige separação/rotulagem |
| Proposta sem efeitos | Apenas texto ao orquestrador; proíbe escrita, comandos do projeto, renderização, instalação, buscas externas e acesso a segredos |
| Notação autocontida | Não usa includes remotos nem macros C4 indefinidas; distingue notação local da biblioteca C4-PlantUML não instalada |
| Modelo e esforço | Papel declara `model_reasoning_effort = "medium"`; não fixa modelo/provider |
| Configuração local | `config_file = "agents/c4-diagram-generator.toml"` aponta para arquivo existente; ambos TOMLs parseiam |
| Limites honestos | Catálogo diferencia arquivo configurado, descoberta após confiança e sessão atual; sandbox read-only não é anunciado como isolamento universal de leitura/rede |
| Proveniência | Link doador fixa commit, atribui autoria e descreve substituições de persona/modelo/FDD |

O schema local disponível em `/tmp/prospero-codex-schema.json` descreve `AgentRoleToml.config_file` relativo ao `config.toml` que o define e permite `description`/`config_file`; o adaptador segue essa estrutura. Essa inspeção não verifica a origem do cache nem substitui evidência de carregamento pelo CLI instalado. A verificação comportamental e de CLI cabe ao [QA P07](P07-testes.md), ainda em andamento ao emitir esta revisão. A existência do link não é prova de resultado aprovado.

Não há nova dependência, hook, alteração pessoal, comando automático, credencial ou endpoint. O texto preserva revisão independente, ausência de autorização para PR/merge e responsabilidade do orquestrador pela gravação. Não se identificou regressão de contrato ou ampliação indevida de escopo nos arquivos revisados.

## Identidade dos arquivos novos

| Arquivo | SHA-256 |
| --- | --- |
| `docs/agentes/README.md` | `2e08e4b677ecbf1ac1d1e0645326bd1413eb5a980de3e8ae8735f53da924c5c5` |
| `docs/agentes/c4-diagram-generator.md` | `3344754a0fd7a90250dfaf88ca922ce98f13ade0845e7f26058fa4b81f5372a0` |
| `.codex/config.toml` | `7ce0155fed8a2d56c6a174f804b50808cc72009fc87e5b0e0edc84fc84a1d75c` |
| `.codex/agents/c4-diagram-generator.toml` | `ae74a491752871b96439c920882530042364ee9a6ad085d057ef48fab66d4120` |

Mudanças nesses arquivos exigem avaliar revisão complementar. Dispensa de XLSX: ADR-004, documentação/configuração sem novo comportamento funcional por API. A revisão não avalia a futura P08 nem garante renderização PlantUML.
