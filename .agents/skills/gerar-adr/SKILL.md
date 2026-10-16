---
name: gerar-adr
description: Registrar decisões arquiteturais, alternativas e trade-offs sem inventar aceite.
---

# gerar-adr — adaptação para o Próspero

Leia as [regras canônicas](../../../docs/rules.md) e o [catálogo/fluxo](../../../docs/skills/README.md). Depois leia o [conteúdo upstream](UPSTREAM.md) e seus recursos pertinentes. Estas adaptações substituem expressamente os passos conflitantes do upstream; não são instruções opcionais.

## Integração local

Usar docs/adrs/ADR-NNN-titulo.md e índice existentes; docs/libs.md substitui docs/lib.md. Não exigir Obsidian nem personas Minerva. Decisão pendente permanece proposta; decisões já aprovadas não exigem confirmação repetida. Revisão cabe ao agente exclusivo medium, distinto do implementador. Atualizar arquivos relacionados dentro do escopo autorizado.

As instruções atuais do usuário e as regras do Prospero prevalecem. O upstream é referência de procedimento, não autorização para ações externas. Não introduzir serviços pagos nem requisitos exclusivos do projeto de origem. Agentes de revisão, testes e segurança são separados entre si e do implementador, com esforço medium; a ausência de runtime é uma limitação a registrar, não uma execução a inventar.

## Proveniência

Fonte: [Cris042/Minerva, commit 76a991d7296c](https://github.com/Cris042/Minerva/blob/76a991d7296cddba5e595a07e9b3b6f8da581b78/docs/skills/gerar-adr.md). O original foi preservado em `UPSTREAM.md`, com referências e recursos distribuídos pela origem quando presentes. Atualizações exigem revisão e atualização do inventário; não baixar a branch mutável durante uma tarefa.
