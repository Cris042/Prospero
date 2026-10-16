# Skills do Próspero

Este diretório contém 14 skills do projeto, adaptadas e com origens fixadas. O [catálogo completo](../../docs/skills/README.md) define gatilhos, fluxo e responsáveis; as [regras canônicas](../../docs/rules.md) e os [ADRs](../../docs/adrs/README.md) prevalecem sobre exemplos e procedimentos de terceiros.

## Entrada de toda tarefa

Comece por [refinar-task](refinar-task/SKILL.md): consultar a memória e os contratos e reunir perspectivas independentes de arquitetura, testes, segurança, operação e viabilidade. Consolidar antes de implementar. Não manter arquivo paralelo de continuidade.

## Catálogo rápido

| Skill | Quando usar |
| --- | --- |
| [refinar-task](refinar-task/SKILL.md) | Início obrigatório de cada nova tarefa do roadmap. |
| [verification-planning](verification-planning/SKILL.md) | Definir afirmações e evidências verificáveis. |
| [worktrees](worktrees/SKILL.md) | Isolar mudanças arriscadas ou paralelas com Git. |
| [iss-audit](iss-audit/SKILL.md) | Validar issues e reproduzir alegações. |
| [deep-research](deep-research/SKILL.md) | Briefing e organização de pesquisa fornecida. |
| [gerar-adr](gerar-adr/SKILL.md) | Registrar decisões, alternativas e trade-offs. |
| [tdd-workflow](tdd-workflow/SKILL.md) | RED → GREEN → REFACTOR e evidências de comportamento. |
| [criar-migration](criar-migration/SKILL.md) | Alterar schema/dados com recuperação documentada. |
| [loop-engineering](loop-engineering/SKILL.md) | Iterar com hipótese e sucesso mensurável. |
| [humanizer](humanizer/SKILL.md) | Melhorar redação sem alterar fatos. |
| [pr-audit](pr-audit/SKILL.md) | Revisar a entrega antes da decisão de merge. |
| [security-audit](security-audit/SKILL.md) | Auditar ameaças, dependências, dados e CI. |
| [subir-sistema](subir-sistema/SKILL.md) | Subir e diagnosticar o Compose com verificação independente. |
| [release](release/SKILL.md) | Preparar publicação somente após pedido explícito. |

## Como aplicar

Leia primeiro o `SKILL.md` local, depois seu `UPSTREAM.md` e apenas os recursos relevantes. Os arquivos upstream preservam a referência, não autorizam merge, release, mensagens ou comandos externos. A instalação não executa scripts nem workflows aninhados.

Revisão, testes e segurança usam três agentes distintos com esforço **medium**, separados do implementador. O refinamento pode distribuir os cinco pareceres em lotes quando o runtime tiver poucos slots.

O ai-memory é uma ferramenta separada, integrada ao fluxo para recuperar contexto e registrar decisões operacionais/handoffs. Seu uso não substitui documentação vigente nem comprova execução de testes. [Origens e checksums](../../docs/skills/origens.json) permitem conferir a proveniência; atualizações exigem revisão explícita.
