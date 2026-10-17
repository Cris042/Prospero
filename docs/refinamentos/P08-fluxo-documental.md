# Refinamento P08 — Fluxo documental e playbook

Data: 2026-10-17. Usuário pediu guia PRD/HLD/ADR, análise de aderência, duas skills e avaliação dos playbooks Minerva para consolidar uma referência única. Depois autorizou criar PRD/HLD conforme necessidade.

## Fontes e brief

Brief comum: revisar sistema/documentos, definir PRD WHAT/WHY, HLD HOW macro e ADR WHY de decisão; documentação proporcional, marcadores de divergência, sugestão de outros documentos com necessidade concreta e simplicidade; fontes Minerva fixadas, sem ferramentas/personas/custos importados. Fontes locais: AGENTS, rules, roadmap, libs, PROJETO, ADRs 001–005 e código/manifests. Memória real consultada em `prospero/Prospero`, página working/fundacao-e-governanca.md e buscas documentais ampliadas; não havia guia PRD/HLD prévio.

As cinco análises independentes medium foram recebidas antes de implementar P08. Este registro escrito da consolidação foi produzido após a criação inicial dos adaptadores/documentos, e não é apresentado como artefato anterior às alterações. P05/P07 foram preservadas em branches/PRs próprios.

## Pareceres efetivos

| Agente | Perspectiva | Posição | Critérios incorporados |
| --- | --- | --- | --- |
| docs_arquitetura | Arquitetura | Seguir com ressalvas | Fronteiras PRD/HLD/ADR, preservar IDs, distinguir planejado e real, sem ADR ritual. |
| docs_qa | QA | Seguir com ressalvas | Links/hashes, inventário de 16 skills, exercícios de suficiência/conflito, dispensa XLSX documental. |
| docs_seguranca | Segurança | Seguir com ressalvas | Não importar autoridade/segredos, defaults cloud, Obsidian ou aprovações automáticas. |
| docs_operacao | Operação | Seguir com ressalvas | Referência seletiva, evidência por hipótese, nenhuma stack ou ferramenta nova. |
| docs_viabilidade | Viabilidade | Seguir com ressalvas; complemento seguir | Evitar duplicação; extrair PRD/HLD base após autorização e manter PROJETO como índice. |

Não houve divergência bloqueante. A ressalva inicial de evitar dois documentos duplicados foi resolvida pela extração de requisitos/desenho existentes, sem nova regra de produto. Complemento de viabilidade confirmou essa fronteira após a autorização explícita; não houve nova decisão de stack/topologia.

## Entrega e aceite

- Guia com responsabilidades, matriz, fontes de verdade, checklist de entrada/conclusão e marcadores DOC-GAP/DOC-MISMATCH/ADR-VIOLATION/PRD-MISMATCH com encaminhamento.
- PRD-001/HLD-001 extraídos do PROJETO, preservando RF01–RF10/RNF01–RNF08, exclusões, metas e lacunas. A proposta inicial de manter PROJETO como visão geral foi substituída pelo complemento abaixo.
- Skills gerar-prd/gerar-hld adaptadas com origem/hash; rules/AGENTS/refinar-task/catálogos atualizados. Sem continuidade, ferramentas pagas ou entrevistas rituais.
- Um playbook seletivo consolidando os cinco originais, sem copiar enciclopédia nem promover receitas financeiras como TTL de reservas.
- Análise de aderência real com fontes e estado; revisão, testes documentais e segurança em agentes separados medium.

## Plano de evidências e limites

QA verifica links, hashes, conservação de requisitos, exemplos de aplicação e exercícios das skills com entradas suficientes/incompletas/conflitantes. Revisão verifica separação de responsabilidades, rastreabilidade, consistência e proporcionalidade. Segurança verifica instruções externas, dados sensíveis e ampliação de autoridade. Sem builds Java/Angular, stress ou nova planilha: alteração documental/configuração de skills, ADR-004. PR próprio empilhado, sem merge automático.

## Complemento de escopo antes da migração final

O usuário autorizou explicitamente remover PROJETO e proposta original e substituí-los por HLD do ecossistema e quatro HLDs locais. Antes dessa migração dependente, foram recebidos cinco pareceres complementares medium: docs_arquitetura, docs_qa, docs_seguranca, docs_operacao_complemento e docs_viabilidade_complemento, todos seguir com ressalvas. Brief comum: preservar conteúdo relevante, origem histórica no Git, stack vigente, estado atual/futuro e links; nenhuma decisão financeira nova. Ressalvas consolidadas na matriz de migração e dicionários preliminares. Essa instrução substitui a opção anterior de manter PROJETO como índice.

Também solicitado índice de estrutura no README: mapa de pastas e arquivos mantidos, sem listar conteúdo privado/gerado como fonte de código. Trata-se da navegação documental da mesma tarefa, sem mudança no runtime.

Aceite complementar: um PRD e cinco HLDs, oito estruturas históricas de dados preservadas como referência proposta, requisitos/atores/exclusões e pendências conservados, dois documentos anteriores removidos com links ativos atualizados e origem histórica rastreável. O índice no README explica cada arquivo mantido e arquivos gerados por categoria.
