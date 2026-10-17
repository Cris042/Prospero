# P08 — Migração e análise de aderência documental

## Escopo e fontes

Inspeção estática do autor, sujeita à revisão/QA/segurança independentes. Fontes: documentos anteriores em `c84aea222a17c28422e0050df9f477b487980470`, ADRs 001–005, manifests, código técnico, roadmaps e evidências existentes. Não houve nova execução da stack nem teste financeiro nesta migração.

O usuário autorizou remover `PROJETO.md` e `prospero-arquitetura.md` após substituí-los por PRD, HLD do ecossistema e quatro HLDs de MS. A origem permanece no [Git — PROJETO](https://github.com/mclovin137/Prospero/blob/c84aea222a17c28422e0050df9f477b487980470/PROJETO.md) e [Git — proposta](https://github.com/mclovin137/Prospero/blob/c84aea222a17c28422e0050df9f477b487980470/prospero-arquitetura.md), como histórico, sem cópia paralela vigente.

## Mapa da migração

| Fonte / seção | Destino ou tratamento | Estado |
| --- | --- | --- |
| PROJETO: objetivo e escopo funcional | PRD-001, contexto/escopo/exclusões | Preservado |
| PROJETO: arquitetura e responsabilidades | HLD-001 e HLD-002–005 | Preservado e detalhado |
| PROJETO: tabelas RF/RNF | PRD-001 com mesmos 18 IDs/descrições | Preservado |
| PROJETO: contratos, testes, entregas | HLD-001, HLDs locais, rules e roadmap | Referenciado sem duplicar políticas |
| Proposta §1: escopo/simplificações | PRD-001, escopo e atores | Preservado; stops no ME no HLD-004 |
| Proposta §1: reaproveitamento | Pesquisa P05 e HLD-003/005 | Substituído pela análise efetiva do doador; não importar cálculo incompatível |
| Proposta §2: atores | PRD-001, seis papéis conceituais | Preservado; sem RBAC/telas fictícios |
| Proposta §3–4: requisitos | PRD-001 e aceite por RF no roadmap | Preservado, sem alegar implementação |
| Proposta §5: tecnologias | HLD-001, libs e ADR-005 | Substituído por stack adotada; Spring/Java25/Angular18/actuator não vigentes |
| Proposta §6.1–2: serviços/decisões | HLD-001 e quatro HLDs; ADRs | Preservado sob decisões vigentes |
| Proposta §6.3: tópicos | HLD-001, catálogo de candidatos históricos | Preservado como proposta, sem contrato implementado |
| Proposta §6.4: perfil visual de qualidade | HLD-001, trade-offs e RNFs no PRD | Sem valores inventados: gráfico antigo não era medição |
| Proposta §7: modelo e dicionário | HLD-002 FO: ativo/ordem/outbox; HLD-003 MO: ativo/conta_risco/reserva; HLD-005 BO: ativo/negocio/lancamento/posicao | Oito estruturas preservadas como referência preliminar, sem schema aprovado |
| Proposta §7: livro sem tabela | HLD-004 e ADR-001 | Preservado; banco ME só suporte a definir |
| Proposta §8: padrões/estruturas | ADR-002, HLDs FO/MO/ME/BO | Preservado por responsabilidade; classes e estruturas candidatas rotuladas |
| Proposta §9: tópicos de estudo | PRD: liquidez/impacto/slippage; HLD-001: temas técnicos | Preservado o relevante; narrativa pessoal não é requisito |
| Proposta §10: testes | HLDs e ADR-004; roadmaps P03/P06/V01 | Preservada intenção; jqwik/Testcontainers/k6 não adotados automaticamente |
| Proposta §11: riscos | HLD-001 e seções de recuperação locais | Preservado; não liberar reserva só por timeout |
| Proposta §12: opcionais | HLD-001/004/005 | Alternativas, sem adoção automática de GC/segurança/arquivamento/jobs |
| Proposta §13: 12 semanas | Roadmap vigente | Cronograma descartado, já substituído; nenhuma conclusão retroativa |
| Proposta §14: pastas/classes | HLDs locais, ADR-001 e docs/README.md (índice documental) | Stack/caminhos atuais; nomes futuros são candidatos, sem módulo comum obrigatório |
| Proposta §15.1: precisão/build/ativos/exemplo | PRD, HLD-003/004, libs e P05 | Build resolvido; regras financeiras/exemplo detalhado continuam pendentes |
| Proposta §15.2: ordens/risco | PRD, HLD-002/003/004 | Preservadas combinações, OCO/parciais, referências e corridas |
| Proposta §15.3: persistência/recuperação | HLDs locais + catálogo HLD-001 | Preservados contas/eventos, append-only, tempo/IDs, snapshot/offset e publicação abortada |
| Proposta §15.4: liquidação/segurança/operação | HLD-001/005 e PRD | Preservados calendário, saldos, atores, dashboards e limites de medição |

## Aderência observada e encaminhamento

| Classificação | Fonte e evidência | Impacto / encaminhamento | Responsável / estado |
| --- | --- | --- | --- |
| `[DOC-GAP]` | Antes P08: PROJETO misturava requisitos e desenho, sem PRD/HLD separados | Extrair responsabilidades para PRD-001 e cinco HLDs; adicionar guia e skills | Autor P08; corrigido no candidato, revisão pendente |
| `[DOC-MISMATCH]` | Texto inicial do guia ainda dizia que PROJETO era acervo vigente após extração | Substituir por PRD/HLDs e mapa histórico | Autor P08; corrigido no candidato |
| `[DOC-MISMATCH]` | Rules continha situação antiga de P01 sem remoto; PR #1 já integrado | Corrigir status sem reescrever evidências históricas | Autor P08; corrigido no candidato |
| Lacuna futura, sem violação | RF01–RF10/runner XLSX descritos como planejados; código só técnico | Manter tarefas/decisões bloqueantes no roadmap, não anunciar sistema financeiro operacional | Tarefas RF/P03; planejado |
| Lacuna futura, sem violação | Autorização de negócio e contratos de eventos ainda sem definição | Resolver antes dos endpoints/consumidores pertinentes | RFs/P06; planejado |
| Limite de comprovação | RNF04 e trace financeiro fim a fim não medidos; telemetria atual é fundação | Medir após RFs/P06 na V01; não confundir contêiner saudável com aceite | V01; planejado |
| Limite operacional | CI configurada, runner dedicado ainda pendente | Ativar/validar remoto na P04; evidência local não substitui gate GitHub | P04; pendente |

Não se identificou violação arquitetural na leitura estática do escopo documental; isso não constitui auditoria integral do sistema. ADR-001 mantém o contexto e o link histórico da decisão. Menções de implementação pendente em ADRs datados não são provas de que toda a fundação atual está ausente; status detalhado pertence ao roadmap.

## Exemplos de rastreabilidade

RNF08 → PRD-001 → HLD-001/002–005 → ADR-001/005 → P02/PR #1 → Dockerfiles/Compose e evidências P02. RNF01 → PRD-001 → HLD-003 → ADR-002 → P05/PR #3 → tipos decimais/testes; isso prova somente precisão técnica coberta, não RF03 completa.

Exemplo proporcional: mudança local de Helper usa requisitos/HLD existentes e testes internos, sem PRD novo. Novo fluxo transversal atualiza HLD-001 e contextos afetados. Exemplo hipotético de conflito: REST síncrono entre MS contradiz ADR-001 e deve ser registrado como `[ADR-VIOLATION]`; não foi introduzido por esta tarefa.

Resultados independentes e limites finais estão nos relatórios P08 de testes, revisão e segurança. Este mapa não representa aceite de merge.
