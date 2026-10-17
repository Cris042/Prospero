# Documentação do Próspero

Este índice explica a finalidade das pastas e dos arquivos diretamente em `docs/`. Para executar o ambiente, consulte o [README do projeto](../README.md).

## Por onde começar

Leia o [PRD](prds/PRD-001-simulador-prospero.md) para entender o produto e os requisitos, os [HLDs](hlds/README.md) para conhecer a arquitetura e os [ADRs](adrs/README.md) para entender as decisões. Consulte o [roadmap](roadmap.md) para distinguir funcionalidades planejadas de entregas integradas.

## Pastas

| Pasta | Finalidade |
| --- | --- |
| [adrs/](adrs/README.md) | Decisões arquiteturais, contexto, alternativas, consequências e histórico de substituições. |
| [agentes/](agentes/README.md) | Perfis dos agentes e orientações de uso, incluindo o gerador de diagramas C4. |
| [entregas/](entregas/) | Resumos das entregas realizadas e suas limitações; não substituem o status do roadmap. |
| [evidencias/](evidencias/) | Relatórios de testes, revisão, segurança, aderência e capturas da interface. |
| [hlds/](hlds/README.md) | Desenho do ecossistema e dos quatro microsserviços: stack, componentes, dados, integrações e recuperação. |
| [pesquisas/](pesquisas/) | Análises de referências, alternativas e limites do reaproveitamento de soluções externas. |
| [planos/](planos/) | Escopos técnicos ainda planejados, como carga progressiva e observabilidade. |
| [prds/](prds/) | Problema, objetivos, atores, escopo, requisitos e critérios de aceite do produto. |
| [refinamentos/](refinamentos/) | Briefs, pareceres independentes, lacunas e critérios definidos antes da implementação. |
| [skills/](skills/README.md) | Catálogo, gatilhos, fluxo e proveniência das skills; instruções executáveis por agentes ficam em `.agents/skills/`. |

`reuso/` está vazio neste checkout e não contém documentação versionada; a análise de reaproveitamento vigente está em [pesquisas/reuso-minerva-financas.md](pesquisas/reuso-minerva-financas.md).

## Arquivos nesta pasta

| Arquivo | Finalidade |
| --- | --- |
| [README.md](README.md) | Índice da documentação e explicação da organização desta pasta. |
| [ci.md](ci.md) | Configuração do runner dedicado, isolamento, gates e limitações da CI. |
| [documentacao.md](documentacao.md) | Quando criar ou atualizar PRD, HLD e ADR; rastreabilidade e revisão de aderência. |
| [libs.md](libs.md) | Inventário de bibliotecas, ferramentas, versões adotadas e pendências. |
| [memoria.md](memoria.md) | Configuração, operação, persistência e limites do ai-memory e dos hooks. |
| [playbook-desenvolvimento.md](playbook-desenvolvimento.md) | Referência seletiva para desenvolver e diagnosticar problemas com simplicidade. |
| [roadmap.md](roadmap.md) | Tarefas globais, dependências, critérios de aceite, estado e links dos PRs. |
| [rules.md](rules.md) | Contrato canônico de arquitetura, desenvolvimento, validação e contribuição. |

## Manutenção

Atualize este índice quando adicionar, mover ou remover uma pasta documental ou arquivo diretamente em `docs/`. Os detalhes internos de cada área pertencem ao respectivo índice ou documento. Preserve a distinção entre planejado, implementado e comprovado e siga o [guia documental](documentacao.md).
