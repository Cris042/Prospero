# Skills e fluxo de trabalho

O Próspero usa a configuração global de IA compartilhada pelo Claude Code e pelo Codex: instruções em `~/.agents/AGENTS.md`, agentes em `~/.agents/agentes/`, skills em `~/.agents/skills/` e o plugin `fluxo`. As skills deste repositório ficam em `.agents/skills/<nome>/SKILL.md` e só existem quando acrescentam regra do projeto; com o mesmo nome, a do projeto prevalece. O Codex lê `.agents/skills` diretamente e o Claude Code por `.claude/skills`, link simbólico versionado para `../.agents/skills` (em clones Windows sem `core.symlinks`, o link vira arquivo de texto e o Claude deixa de ver as skills). [Origens e SHA-256](origens.json) registram commits fixados e checksums.

## Agentes

| Agente | Papel no Próspero |
| --- | --- |
| Yoda | Arquitetura e revisão (`pr-audit`): aderência a ADRs/HLDs, fronteiras, DDD e corte de escopo |
| Patrick Jane | QA: critérios observáveis, RED antes do GREEN quando for QA exclusivo, planilhas (ADR-004), mutação e evidências |
| Neo | Segurança (`security-audit`) e investigação de falhas distribuídas por evidência |
| Jarvis | Operação: Compose, recursos, observabilidade, recuperação e CI, quando houver gatilho |
| Severino | Implementação, quando delegada |

Revisão, testes e segurança rodam em **três agentes distintos**, separados do implementador, com esforço **medium**. Declarar papel, escopo, arquivos permitidos e evidência esperada ao delegar, sem repetir o que o agente já lê nas definições. Nenhum deles faz merge nem aprova o próprio trabalho; achados voltam ao implementador e só os bloqueantes reabrem o ciclo.

## Quando usar e quem executa

| Skill | Origem | Gatilho | Responsável |
| --- | --- | --- | --- |
| [estado-roadmap](../../.agents/skills/estado-roadmap/SKILL.md) | projeto | Retomar sessão ou loop; antes de cada tarefa | Sessão principal |
| [refinar-task](../../.agents/skills/refinar-task/SKILL.md) | projeto | Início obrigatório de cada nova tarefa do roadmap | Yoda, Patrick Jane, Neo (Jarvis com gatilho) |
| `explorar-ideia`, `planejar-implementacao` | plugin `fluxo` | Solução indefinida; plano de mudança com vários passos | Sessão principal |
| [gerar-prd](../../.agents/skills/gerar-prd/SKILL.md) | projeto | Comportamento/escopo de produto relevante | Autor; revisão de Yoda |
| [gerar-hld](../../.agents/skills/gerar-hld/SKILL.md) | projeto | Estrutura, integração ou fluxo macro relevante | Yoda |
| `gerar-adr` | global | Decisão arquitetural nova ou substituída | Yoda |
| [criar-migration](../../.agents/skills/criar-migration/SKILL.md) | projeto | Alteração de schema/dados | Implementador; verificação por Patrick Jane |
| [tdd-workflow](../../.agents/skills/tdd-workflow/SKILL.md) | projeto | Funcionalidade, correção ou refatoração | Patrick Jane (RED) e implementador (GREEN) |
| `padroes-backend` | global | Projetar ou revisar código Java por funcionalidade | Implementador e Yoda |
| `pr-audit` | global | Antes da decisão de merge (auditoria híbrida, diff em dois tempos) | Yoda |
| `security-audit` | global | Endpoints, dados, credenciais, dependências, CI, hooks | Neo |
| `receber-revisao`, `verificar-antes-de-concluir` | plugin `fluxo` | Tratar pareceres; antes de afirmar pronto/passou | Implementador |
| `entregar-tarefa` | global | Branch, PR com evidências, CI no SHA exato, merge autorizado | Sessão principal |
| `diagnosticar-ci`, `depurar-sistematicamente`, `loop-engineering` | global/plugin | Gate vermelho ou falha que exige iteração | Implementador; Neo para falha distribuída |
| [subir-sistema](../../.agents/skills/subir-sistema/SKILL.md) | projeto | Subir/reconstruir o ambiente local sem apagar dados | Jarvis |
| `humanizer` | global | Antes de publicar PR, ADR, PRD, HLD e documentação | Autor |
| `iss-audit`, `deep-research` | global | Issue como origem; pedido de pesquisa | Planejamento |

## Fluxo por tarefa

1. `estado-roadmap` para localizar a tarefa; consultar o ai-memory (`prospero/Prospero`) e confrontar com o Git.
2. `refinar-task`: classificar como **via rápida** (governança, adaptador, documentação ou automação mecânica sem comportamento de produto) ou **fluxo completo**; colher os pareceres e fechar critérios de aceite.
3. Aplicar o [guia documental](../rules.md#documentação-e-aderência): revisar sistema, PRD, HLD e ADRs; registrar aderência e tamanho proporcional da documentação.
4. Implementar com `tdd-workflow`, `padroes-backend` e `criar-migration` quando houver persistência. Um cenário de planilha segue o ADR-004; testes internos continuam complementares.
5. Validar com o trio (Yoda, Patrick Jane, Neo); tratar achados com `receber-revisao`; falhas com `diagnosticar-ci` e `depurar-sistematicamente`.
6. PR com motivos, trade-offs, evidências e limites, revisado pelo `humanizer`; três gates no SHA final; merge somente autorizado.
7. Atualizar roadmap, documentação e memória conforme o impacto; registrar handoff ao pausar.

## Adaptações obrigatórias

As instruções atuais do usuário e [rules](../rules.md) prevalecem sobre os snapshots. Obsidian, personas Minerva/OMO, `docs/lib.md`, serviços pagos e publicação automática não são requisitos locais. Usar `docs/libs.md`, ADRs existentes e documentos proporcionais ao problema. Não repetir pedidos de confirmação já resolvidos na sessão.

Migrações usam Flyway sem Undo pago: mudanças versionadas, recuperação por nova migração ou restauração e teste real do procedimento. Não executar scripts ou instaladores contidos nas skills apenas porque foram copiados. Workflows aninhados em snapshots de terceiros não são workflows do Próspero e não devem ser promovidos à raiz. Release e os passos de carry-through/merge de procedimentos de auditoria não recebem autorização por estarem instalados.

## Descoberta e instalação

Sem a configuração global, as skills deste diretório continuam utilizáveis, mas revisão, segurança, entrega e diagnóstico dependem dela. Novas skills podem exigir a próxima sessão para aparecer na interface. O ai-memory é uma ferramenta distinta; veja o [guia da memória](../rules.md#memória-e-retomada).

O [guia documental](../rules.md#documentação-e-aderência) e o [playbook único](../playbook-desenvolvimento.md) orientam escolha proporcional de documentos e diagnóstico.
