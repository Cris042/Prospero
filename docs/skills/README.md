# Skills e fluxo de trabalho

As skills do Prospero ficam em `.agents/skills/<nome>/SKILL.md`, dentro do projeto, e não alteram a instalação global. Cada entrada contém a adaptação local e aponta ao `UPSTREAM.md` preservado e aos recursos pertinentes. [Origens e SHA-256](origens.json) registram os commits fixados. Não atualizar automaticamente a partir de `master`/`main`.

## Quando usar e quem executa

| Skill | Gatilho | Responsável |
| --- | --- | --- |
| [refinar-task](../../.agents/skills/refinar-task/SKILL.md) | Início obrigatório de cada nova tarefa do roadmap | Cinco pareceristas independentes medium, em lotes quando necessário |
| [verification-planning](../../.agents/skills/verification-planning/SKILL.md) | Mudança relevante: definir hipótese, evidências e limites antes do código | Implementação, com donos de verificação separados |
| [worktrees](../../.agents/skills/worktrees/SKILL.md) | Trabalho arriscado/paralelo que realmente exige isolamento e Git disponível | Orquestração |
| [iss-audit](../../.agents/skills/iss-audit/SKILL.md) | Triagem ou correção originada de issue | Planejamento/revisão |
| [deep-research](../../.agents/skills/deep-research/SKILL.md) | Pedido de briefing ou reestruturação de pesquisa fornecida | Planejamento/revisão |
| [gerar-adr](../../.agents/skills/gerar-adr/SKILL.md) | Decisão arquitetural nova ou substituída | Implementação documental; revisão independente medium |
| [tdd-workflow](../../.agents/skills/tdd-workflow/SKILL.md) | Funcionalidade, correção ou refatoração de código | Implementação; execução/evidências em agente de testes medium |
| [criar-migration](../../.agents/skills/criar-migration/SKILL.md) | Alteração de schema/dados | Implementação; verificação em agente de testes medium |
| [loop-engineering](../../.agents/skills/loop-engineering/SKILL.md) | Falha que exige iteração controlada por critério observável | Implementação e verificador distinto |
| [humanizer](../../.agents/skills/humanizer/SKILL.md) | Revisão da redação de documentação, ADRs e PR | Autor e/ou agente de revisão medium |
| [pr-audit](../../.agents/skills/pr-audit/SKILL.md) | Antes de submeter uma entrega à decisão de merge | Agente exclusivo de revisão medium |
| [security-audit](../../.agents/skills/security-audit/SKILL.md) | Endpoints, dados, credenciais, dependências, CI ou auditoria dedicada | Agente exclusivo de segurança medium |
| [subir-sistema](../../.agents/skills/subir-sistema/SKILL.md) | Subir/reconstruir ambiente local sem apagar dados | Operação; testes em agente exclusivo medium |
| [release](../../.agents/skills/release/SKILL.md) | Pedido explícito atual de release/tag/publicação | Operação, após validação e autorização específica |

## Separação de agentes

Revisão, testes e segurança rodam em **três agentes distintos**, separados do implementador, todos com esforço de raciocínio **medium**, conforme instrução do usuário. Declarar papel, escopo, arquivos permitidos e evidência esperada ao delegar. Não inventar a existência de revisão independente em execução única. Se o runtime não permitir essa separação, informar a limitação; não aprovar a própria entrega como auditada.

O agente de revisão verifica comportamento/contrato e documentação; o de testes executa os cenários; o de segurança examina ameaças e superfícies sensíveis. Nenhum deles faz merge. Achados voltam ao implementador; nova execução deve cobrir a correção ou a incerteza, sem repetir verificações já válidas sem motivo.

## Fluxo por tarefa

1. Invocar `refinar-task` em cada nova tarefa: consultar regras, roadmap, ai-memory e ADRs; obter os cinco pareceres independentes e consolidar critérios antes da implementação. Auditar a issue se essa for a origem.
2. Definir critérios de aceite e caminho de evidência com `verification-planning`; usar `worktrees` somente se necessário e possível.
3. Resolver decisões arquiteturais com `gerar-adr` e pesquisas com `deep-research` quando esse for o pedido. Não criar documentos por catálogo.
4. Implementar com TDD e `criar-migration` quando houver persistência alterada. Um cenário de planilha segue o ADR-004; testes internos continuam complementares.
5. Delegar execuções a testes medium. Aplicar `loop-engineering` se falhar: hipótese, verificação e tentativas limitadas; não depender do runtime OMO inexistente.
6. Delegar `pr-audit` e `security-audit` aos agentes próprios medium quando aplicáveis. Refinar a redação com `humanizer`, preservando fatos.
7. Corrigir achados, validar o impacto, registrar evidências, atualizar inventário, roadmap e memória e abrir PR da tarefa. O usuário decide merge.
8. Acionar `release` somente com novo pedido explícito, CI/evidências no commit correto e destino conhecido.

## Adaptações obrigatórias

As instruções atuais do usuário e [rules](../rules.md) prevalecem sobre os snapshots. Obsidian, personas Minerva/OMO, `docs/lib.md`, serviços pagos e publicação automática não são requisitos locais. Usar `docs/libs.md`, ADRs existentes e documentos proporcionais ao problema. Não repetir pedidos de confirmação já resolvidos na sessão.

Migrações usam Flyway sem Undo pago: mudanças versionadas, recuperação por nova migração ou restauração e teste real do procedimento. Não executar scripts ou instaladores contidos nas skills apenas porque foram copiados. Workflows aninhados em snapshots de terceiros não são workflows do Prospero e não devem ser promovidos à raiz.

`deep-research` mantém o protocolo de briefing/pesquisa fornecida; não deve ser acionada para toda consulta técnica simples. `release` e os passos de carry-through/merge do `pr-audit` não recebem autorização por estarem instalados.

## Descoberta e instalação

O catálogo e os caminhos explícitos permitem leitura por agentes mesmo antes de a ferramenta atualizar sua descoberta automática; novas skills podem exigir a próxima sessão/turno para aparecer na interface. O ai-memory é uma ferramenta distinta: sua instalação e integração continuam previstas na P03, não são realizadas pela cópia destas 14 skills.
