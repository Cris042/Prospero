---
name: criar-migration
description: Planejar, criar e verificar migrações SQL com recuperação explícita.
---

# criar-migration — adaptação para o Próspero

Leia as [regras canônicas](../../../docs/rules.md) e o [catálogo/fluxo](../../../docs/skills/README.md). Depois leia o [conteúdo upstream](UPSTREAM.md) e seus recursos pertinentes. Estas adaptações substituem expressamente os passos conflitantes do upstream; não são instruções opcionais.

## Integração local

Usar PostgreSQL16/Flyway, arquivos V<N>__descricao.sql e migrações versionadas imutáveis. Não assumir Flyway Undo pago. Documentar recuperação por nova migração ou restauração, com cópia recuperável quando houver dados; não declarar rollback testado sem evidência. Mudança destrutiva exige autorização específica. Substituir Obsidian por docs do projeto e testar em agente exclusivo medium. A task aprovada pode conter o contrato de dados sem exigir FDD ritualístico.

As instruções atuais do usuário e as regras do Prospero prevalecem. O upstream é referência de procedimento, não autorização para ações externas. Não introduzir serviços pagos nem requisitos exclusivos do projeto de origem. Agentes de revisão, testes e segurança são separados entre si e do implementador, com esforço medium; a ausência de runtime é uma limitação a registrar, não uma execução a inventar.

## Proveniência

Fonte: [Cris042/Minerva, commit 76a991d7296c](https://github.com/Cris042/Minerva/blob/76a991d7296cddba5e595a07e9b3b6f8da581b78/docs/skills/criar-migration.md). O original foi preservado em `UPSTREAM.md`, com referências e recursos distribuídos pela origem quando presentes. Atualizações exigem revisão e atualização do inventário; não baixar a branch mutável durante uma tarefa.
