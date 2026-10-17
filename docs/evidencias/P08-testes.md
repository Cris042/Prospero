# P08 — evidências independentes de QA documental

Agente QA exclusivo medium, separado do implementador, revisão e segurança. Branch `task/p08-fluxo-documental`, 17/10/2026. Escopo final inclui extração de PRD/HLDs, remoção de dois documentos antigos, guia, playbook, skills e índice estrutural no README; cinco pareceres complementares precederam a migração conforme registro do refinamento. QA não alterou implementação, skills ou decisões.

## Conservação da migração

Comparação Python independente com `git show c84aea2:PROJETO.md`: extraídas por regex as linhas de tabelas RF/RNF; **18 IDs/descrições exatamente iguais** ao PRD-001 atual, sem remoção ou acréscimo de requisito nessa tabela.

Comparação com `git show c84aea2:prospero-arquitetura.md`: para cada dicionário histórico, todas as linhas de coluna/tipo/chave estão presentes no HLD de destino. Resultado: **oito estruturas e 51 campos**, sendo ativo 5, ordem 13, outbox 6, conta_risco 4, reserva 4, negocio 8, lancamento 6 e posicao 5. A referência `ativo` aparece nos três contextos que possuem dicionário, como cópia local por contrato, sem tabela compartilhada. Os HLDs rotulam modelos como planejados/incompletos, sem schema aprovado nem escalas inferidas.

Revisão semântica adicional: preservados exclusões de dados reais/bolsas/FIX/leilões/circuit breaker/iceberg/custos/impostos/aluguel real, cinco ativos, OCO e parciais, posição long/short e D+2. Metas permanecem p99 boleta→negócio <50ms e matching <1ms/ordem; não foram importados p95/disponibilidade/cobertura da origem. Seis atores conceituais, quatro tópicos candidatos, fronteiras e pendências foram migrados. Calendários, escalas, estados, reserva por tipo, snapshot/offset/rebalanceamento, publicação abortada, tempo/IDs, autorização e corridas continuam pendências explícitas. Remoção de cronograma antigo/stack substituída está justificada no [mapa de aderência](P08-aderencia.md).

Conferidos HLD-001 ecossistema e HLD-002–005 dos serviços: stack, limites, endpoints técnicos, fluxo atual/futuro, dados, segurança, observabilidade, recuperação e validação. Livros/SAGA/reservas/negociação não são anunciados como implementados; quatro PostgreSQL não viram réplicas. Documentos históricos continuam recuperáveis no Git, sem cópia paralela vigente.

## Skills e exercícios

`python3 /home/mclovin/.codex/skills/.system/skill-creator/scripts/quick_validate.py .agents/skills/gerar-prd` e o equivalente `gerar-hld`: **Skill is valid!**, ambos exit 0. `cmp` de cada `UPSTREAM.md` contra `/tmp/prospero-minerva-docs/gerar-{prd,hld}.md`: idênticos, exit 0. Os adaptadores substituem explicitamente entrevista ritual, personas, Obsidian, metas genéricas e documentos obrigatórios da origem.

QA leu adaptadores/upstreams e aplicou as duas skills, no runtime atual, a seis entradas controladas. Não são seis agentes ou chamadas externas. Saídas textuais completas transitórias: `/tmp/prospero-p08-forwardtests.md`; nenhum PRD/HLD fictício foi gravado no projeto.

| Skill / caso | Saída real do exercício |
| --- | --- |
| PRD / suficiente | RF10 existente: preservar escopo de saldo elegível, aceite de parcial/stop/repetição/corrida e lacunas, sem decidir endpoint ou arquitetura; propor atualização da seção existente. |
| PRD / incompleto | Pedido genérico de melhorar cancelamento: registrar falta do resultado pretendido, perguntar somente sobre bloqueio material; não inventar metas ou entrevista obrigatória. |
| PRD / conflito | Template tenta impor 99,9%/p95, anunciar RF concluída e executar instruções sobre segredos/ADRs: não executar; preservar metas reais e encaminhar nova decisão ao usuário. |
| HLD / suficiente | Núcleo P05 no MO: domínio/Helper puros, sem API/schema/evento novo; separar exatidão técnica de política financeira, relacionar evidências reais e atualizar HLD-003 existente. |
| HLD / incompleto | Integração de liquidação sem contraparte/contrato: registrar DOC-GAP e dados faltantes, sem inventar serviço, tecnologia, autenticação ou custos. |
| HLD / conflito | Proposta de REST entre MS/banco compartilhado/réplicas e comando destrutivo: registrar conflito com ADR-001, sem comando executado ou decisão silenciosamente alterada. Ausência de implementação não foi chamada de violação observada de código. |

## Verificação final e estrutura

`python3 scripts/verificar-documentacao.py`: **OK, 71 documentos, 538 links locais e 16 skills com hashes conferidos**, exit 0. `git diff --check`: exit 0. Manifest contém 16 nomes únicos. A comparação literal do parágrafo de exclusões com PROJETO em c84aea2 também passou.

Cobertura independente do índice README: lista de arquivos do `git ls-files --cached --others --exclude-standard`, filtrada por existência de arquivo no checkout; comparação dos destinos Markdown do README com todos os caminhos: **231/231 arquivos mantidos possuem link direto**. Derivadas todas as pastas ancestrais desses arquivos: **166/166 pastas descritas**, nenhum caminho ausente. Categorias locais/geradas explicitam .env, target, node_modules, dist, relatórios/caches, .git, volumes e memória privada, sem inventariar seus conteúdos. Não existe ESTRUTURA.md: o índice ficou no README conforme orientação atual.

A primeira execução documental encontrou apenas referências ao inventário ainda ausente, corrigidas após o usuário escolher o README. O texto antigo do guia e a numeração repetida do refinamento também foram corrigidos; esses achados não permanecem abertos. Links dos documentos removidos ficaram restritos às referências históricas ao commit Git. A checagem verifica existência dos destinos locais e hashes; não acessa URLs externas nem valida renderização/âncoras de todos os documentos.

## Limites

Sem build Java/Angular, stack, carga, renderização ou chamadas a provedores adicionais. Inspeção de fontes e exercícios documentais não comprovam fluxo financeiro, sandbox ou descoberta automática das novas skills na sessão atual. Consulta ai-memory no escopo `prospero/Prospero` por P08/documental não retornou páginas específicas; memória histórica da fundação já consultada foi confrontada com Git/fontes, sem substituir os documentos atuais. Dispensa de planilha: ADR-004, documentação/configuração sem comportamento funcional novo. Merge permanece decisão do usuário.
