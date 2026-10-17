# HLDs do Próspero

A arquitetura vigente se organiza em um desenho transversal e quatro contextos independentes. Os documentos distinguem fundação implementada, funcionalidades planejadas e decisões pendentes; o [PRD](../prds/PRD-001-simulador-prospero.md) mantém os requisitos, e os [ADRs](../adrs/README.md), seus motivos.

| HLD | Escopo |
| --- | --- |
| [001 — Ecossistema](HLD-001-arquitetura-prospero.md) | Stack, Angular, infraestrutura, comunicação e atributos transversais |
| [002 — Front office](HLD-002-front-office.md) | Entrada, ordens, trajetória e SAGA |
| [003 — Middle office](HLD-003-middle-office.md) | Validação, risco e reservas |
| [004 — Matching engine](HLD-004-matching-engine.md) | Livro, sequência, eventos e recuperação |
| [005 — Back office](HLD-005-back-office.md) | Negócio, razão, posição e liquidação |

[Guia documental](../documentacao.md), [playbook](../playbook-desenvolvimento.md) e [migração/aderência](../evidencias/P08-aderencia.md). Cada evolução altera somente os documentos necessários; não criar outro HLD sem necessidade concreta.
