---
name: release
description: Preparar release somente quando o usuário pedir uma versão/publicação.
---

# release — adaptação para o Próspero

Leia as [regras canônicas](../../../docs/rules.md) e o [catálogo/fluxo](../../../docs/skills/README.md). Depois leia o [conteúdo upstream](UPSTREAM.md) e seus recursos pertinentes. Estas adaptações substituem expressamente os passos conflitantes do upstream; não são instruções opcionais.

## Integração local

Instalação desta skill não autoriza release, tag, push, merge ou publicação. Exigir pedido atual explícito e verificar CI no SHA exato. Publicar é uma ação distinta de implementar uma tarefa.

As instruções atuais do usuário e as regras do Prospero prevalecem. O upstream é referência de procedimento, não autorização para ações externas. Não introduzir serviços pagos nem requisitos exclusivos do projeto de origem. Agentes de revisão, testes e segurança são separados entre si e do implementador, com esforço medium; a ausência de runtime é uma limitação a registrar, não uma execução a inventar.

## Proveniência

Fonte: [akitaonrails/my-skills, commit 7b8a5e5e0259](https://github.com/akitaonrails/my-skills/blob/7b8a5e5e0259da8cabcdaf806cca9d1d55abf20a/release/SKILL.md). O original foi preservado em `UPSTREAM.md`, com referências e recursos distribuídos pela origem quando presentes. Atualizações exigem revisão e atualização do inventário; não baixar a branch mutável durante uma tarefa.
