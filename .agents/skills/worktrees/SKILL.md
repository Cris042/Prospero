---
name: worktrees
description: Isolar trabalho arriscado ou paralelo em Git worktrees.
---

# worktrees — adaptação para o Próspero

Leia as [regras canônicas](../../../docs/rules.md) e o [catálogo/fluxo](../../../docs/skills/README.md). Depois leia o [conteúdo upstream](UPSTREAM.md) e seus recursos pertinentes. Estas adaptações substituem expressamente os passos conflitantes do upstream; não são instruções opcionais.

## Integração local

Usar somente com Git inicializado e necessidade real de isolamento. O runtime OMO e as personas upstream não existem aqui: usar ferramentas disponíveis e agentes separados apenas quando a tarefa os exigir. Preservar alterações existentes. Não criar/remover branches ou worktrees destrutivamente por rotina; integração não autoriza merge/publicação.

As instruções atuais do usuário e as regras do Prospero prevalecem. O upstream é referência de procedimento, não autorização para ações externas. Não introduzir serviços pagos nem requisitos exclusivos do projeto de origem. Agentes de revisão, testes e segurança são separados entre si e do implementador, com esforço medium; a ausência de runtime é uma limitação a registrar, não uma execução a inventar.

## Proveniência

Fonte: [akitaonrails/my-skills, commit 7b8a5e5e0259](https://github.com/akitaonrails/my-skills/blob/7b8a5e5e0259da8cabcdaf806cca9d1d55abf20a/worktrees/SKILL.md). O original foi preservado em `UPSTREAM.md`, com referências e recursos distribuídos pela origem quando presentes. Atualizações exigem revisão e atualização do inventário; não baixar a branch mutável durante uma tarefa.
