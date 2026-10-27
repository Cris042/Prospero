# Skills do Próspero

O Próspero usa a configuração global de IA (Claude Code e Codex), com skills em `~/.agents/skills`, o plugin `fluxo` e os agentes Yoda, Severino, Patrick Jane, Neo e Jarvis em `~/.agents/agentes`. Este diretório guarda só as **sobreposições com conteúdo do projeto**; uma skill daqui prevalece sobre a global de mesmo nome. O [catálogo completo](../../docs/skills/README.md) descreve gatilhos, fluxo e responsáveis; as [regras canônicas](../../docs/rules.md) e os [ADRs](../../docs/adrs/README.md) prevalecem sobre procedimentos de terceiros.

| Skill | Quando usar | O que tem de específico |
| --- | --- | --- |
| [estado-roadmap](estado-roadmap/SKILL.md) | Ao retomar e antes de cada tarefa | Resume em uma chamada o estado das tarefas e os PRs abertos |
| [refinar-task](refinar-task/SKILL.md) | Início obrigatório de cada nova tarefa do roadmap | Classificação via rápida/fluxo completo, pareceres de Yoda, Patrick Jane e Neo (Jarvis com gatilho), memória `prospero/Prospero` |
| [tdd-workflow](tdd-workflow/SKILL.md) | Funcionalidade, correção ou refatoração | QA exclusivo escreve o RED; planilhas conforme ADR-004 |
| [criar-migration](criar-migration/SKILL.md) | Alterar schema/dados | PostgreSQL 16, Flyway, recuperação documentada sem Flyway Undo |
| [gerar-prd](gerar-prd/SKILL.md) | Requisitos e aceite de produto | Preserva RF01–RF10/RNF01–RNF08 |
| [gerar-hld](gerar-hld/SKILL.md) | Estrutura e fluxos macro | Quatro serviços/bancos, Kafka, execução local sem custo |
| [subir-sistema](subir-sistema/SKILL.md) | Subir e diagnosticar o Compose | Preparação, build, verificação independente sem apagar dados |

Revisão, auditoria de segurança, depuração, diagnóstico de CI, entrega, ADR, humanizer e demais procedimentos vêm da configuração global. Leia primeiro o `SKILL.md`, depois o `UPSTREAM.md` e só os recursos relevantes; os arquivos upstream preservam referência e não autorizam merge, release ou comandos externos. [Origens e checksums](../../docs/skills/origens.json) permitem conferir a proveniência.
