# P08 — revisão independente

Data: 2026-10-17. Agente exclusivo de revisão, esforço medium, separado do implementador, QA e segurança. Aplicada `pr-audit`, adaptação local e upstream preservado. Candidato local da branch `task/p08-fluxo-documental`, base `c84aea222a17c28422e0050df9f477b487980470`. Nenhum documento de implementação foi editado pelo revisor; este arquivo é seu relatório.

## Resultado

**Sem achados arquiteturais bloqueantes no conjunto inspecionado.** Guia, skills, PRD e cinco HLDs organizam o contrato existente, preservando requisitos, responsabilidades e pendências. O índice de arquivos no README também foi inspecionado após sua geração. O encaminhamento final depende das evidências independentes de QA/segurança. Esta inspeção não autoriza merge nem comprova execução de aplicação ou descoberta automática de skills.

Achado de baixa gravidade encaminhado ao autor: o PRD mencionava cinco ativos nas pendências, mas omitia no escopo a expressão explícita dos originais, **cinco ações fictícias**. Recomendada a restauração dessa restrição para evitar interpretar outros instrumentos como incluídos. Estado: corrigido pelo autor e conferido no parágrafo de contexto do PRD.

## Escopo e fontes

Lidos os dois documentos removidos na base Git, o guia documental, PRD-001, HLD-001–005 e índice, playbook consolidado, mapa P08 de migração/aderência, refinamento inicial e complemento, adaptadores gerar-prd/gerar-hld e respectivos upstreams. Revisados diffs de AGENTS/rules, README, ADR-001/índice, perfil C4, catálogos, inventário de skills/libs, refinar-task e script documental. Confrontadas descrições técnicas com Compose, configuração Quarkus, código da fundação e ADRs 001–005. Esta revisão é documental e estática, não auditoria integral do produto.

Memória: consulta real via `bash scripts/memoria.sh`, workspace `prospero`, projeto `Prospero`. Páginas completas de fundação/governança e reuso já recuperadas nesta sessão foram confrontadas com os arquivos. Nova busca `documentação OR P08 OR HLD` retornou vazia; não se presumiu que a migração estivesse registrada na memória.

Comandos de inspeção: `git status --short`, `git rev-parse HEAD`, `git branch --show-current`, `git show c84aea2:<arquivo>`, diffs/numstat, `git diff --check`, leitura de arquivos e buscas `rg`. O diff check terminou com exit 0 e sem saída. Novos arquivos não rastreados foram lidos explicitamente. Execução do verificador, conferência automatizada de hashes/links, exercícios de skills e inventário do README pertencem ao QA independente; não são declarados como executados pelo revisor.

## Avaliação do contrato

| Critério | Resultado da inspeção |
| --- | --- |
| Separação PRD/HLD/ADR | Produto/aceite no PRD, organização/fluxos nos HLDs e motivos/alternativas nos ADRs; guia não exige documento novo por ritual |
| Rastreabilidade | RF01–RF10/RNF01–RNF08 conservados; links para tarefas, decisões e evidências; mapa cobre as seções dos originais com destino ou substituição justificada |
| Preservação histórica | Origem do ADR-001 aponta a blob imutável no Git; nota de migração não reescreve contexto, decisão ou alternativas; índice preserva substituições tecnológicas |
| Estado implementado/futuro | Fundação, núcleo técnico P05 presente na branch e RFs futuras diferenciados; Kafka/telemetria preparados não são chamados de fluxo financeiro completo |
| Dados preliminares | Oito estruturas históricas migradas e rotuladas incompletas; bigint não determina escala; não foram criadas tabelas, constraints ou contratos aprovados implicitamente |
| Fronteiras | FO coordena SAGA; MO risco/reserva; ME sequência/livro; BO razão/posição/liquidação; quatro bancos próprios e integração de negócio por Kafka |
| Falhas e recuperação | Preservadas corridas/timeout, conta versus ativo, publicação abortada/snapshot/offset, participantes nos negócios, append-only versus estado de liquidação e calendário |
| Stack | Quarkus/Java26/JPA e manifests atuais prevalecem; Java25/Spring/JDBC e ferramentas históricas não são revividos como adoção |
| Simplicidade | Quatro HLDs locais atendem pedido explícito; guia orienta atualizar fontes existentes e justificar artefatos adicionais |
| Divergências | Quatro marcadores têm significado, fonte/evidência/impacto e encaminhamento; código não ganha autoridade automática sobre requisito/ADR |
| Skills | Adaptadores substituem entrevistas/Obsidian/personas/defaults de disponibilidade e metas do upstream; proveniência é fixada por commit e arquivos/hash inventariados |
| Playbook | Seleção autoral dos cinco originais, com utilidade por tarefa/sintoma e exclusões explícitas; sem TTL que libere reserva cegamente ou adoção automática de cloud/cluster |

Nenhuma decisão nova de negócio, persistência ou topologia foi identificada. Candidatos históricos de tópicos/classes/estruturas estão rotulados como propostas. A reorganização autorizada não requer novo ADR. A ausência de RF planejada não foi convertida em violação.

## Conferência final do índice

O README conserva comandos operacionais e acrescenta índice de pastas/arquivos com finalidade, separado por componente, além de exclusões para segredos, builds, dependências instaladas, caches e dados privados. Fontes upstream são identificadas como referência sem execução automática. Pacotes vazios e fundação não são descritos como funcionalidades financeiras completas. A contagem exaustiva e os links do inventário ficam no QA; esta revisão examinou sua organização e descrições. A alteração de localização foi autorizada pelo usuário, substituindo a opção anterior de arquivo ESTRUTURA separado.

Uma menção editorial residual a ESTRUTURA no aceite do refinamento foi encaminhada ao autor para apontar ao índice no README; não altera arquitetura nem requisito.

## Limites e encerramento

Manter evidências históricas e upstreams preservados pode deixar menções textuais a documentos antigos; isso não cria referência normativa vigente. Links históricos devem continuar identificados e apontar ao Git. O verificador checa existência de caminhos locais, não valida todo conteúdo/âncora ou funcionamento de URL remota.

Dispensa de nova planilha: ADR-004, documentação/configuração sem mudança funcional por API. Não houve build Java/Angular, stress, execução financeira, renderização, alteração de dependência de runtime, merge ou release nesta revisão.
