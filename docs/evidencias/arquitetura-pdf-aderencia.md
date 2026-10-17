# Comparação com o PDF de arquitetura

Data: 2026-10-17. Escopo: leitura das 14 páginas físicas de `prospero-arquitetura.pdf`, versão 1.0 de 17/10/2026, confronto com PRD-001, HLDs 001–005, ADRs 001–005, README, roadmaps, manifests, migrações e classes técnicas. As figuras de arquitetura, MER e DER também foram inspecionadas visualmente. Não houve execução financeira ou nova medição de desempenho.

Fonte fornecida pelo usuário fora do repositório; SHA-256: `98b76ea49f7b823afb8ca250e88fba39c336ff2b72e4ac6f7e28b7b6e3e20210`. A capa não é numerada; referências abaixo usam **páginas físicas**, incluindo a capa. O PDF não foi copiado para o Git. A [migração P08](P08-aderencia.md) compara a proposta Markdown histórica, cujo conteúdo adicional não deve ser confundido com seções deste PDF.

## Resultado e rastreabilidade

A documentação é coerente com a visão funcional do PDF, sob as substituições explícitas dos ADRs. Isso não significa implementação do produto: hoje existem fundação técnica e conversão decimal pura; RF01–RF10, SAGA, livro e liquidação continuam planejados. A memória `working/p05-p08-entregas.md` foi consultada e confrontada com o checkout; o pedido atual autoriza acrescentar diagramas ao README, antes mantido sem inventário completo.

| PDF | Referência vigente | Comparação |
| --- | --- | --- |
| §1–2, p. 2: escopo, exclusões e seis atores | [PRD-001](../prds/PRD-001-simulador-prospero.md) | Preservados; tabela de simplificações explicitada nesta revisão. Papéis não implicam telas ou RBAC existentes. |
| §3, p. 3: RF01–RF10 | PRD-001 e [roadmap](../roadmap.md) | Dez IDs preservados. Explicitados último preço no túnel RF03 e VWAP do dia RF06. Bootstrap, defasagem e sessão continuam pendentes. |
| §4, p. 3–4: RNF01–RNF08 | PRD-001 e V01 | Oito IDs e metas de latência preservados; RNF05 exige reserva antes da admissão ao matching, sem confundir aceitação HTTP com aprovação de risco. Metas ainda não comprovadas. |
| §5, p. 4: tecnologias | [ADRs](../adrs/README.md), [libs](../libs.md) | Java 25/Spring/JDBC → Java 26/Quarkus/JPA; Angular 18 → 22; agente OTel → extensões Quarkus; `/actuator/prometheus` → `/q/metrics`. Alterações registradas nos ADRs 001/005. |
| §6, p. 5–6: fronteiras, single writer, SAGA, dados e cotações | [HLD-001](../hlds/HLD-001-arquitetura-prospero.md) e HLDs de contexto | Quatro contextos, Kafka, livro em memória/log/snapshots e SAGA no FO preservados como destino. Figura do PDF mostra PostgreSQL FO/MO/BO; ADR-001 acrescenta container próprio de suporte ao ME, sem tabela do livro. |
| §6.3, p. 6: perfil de qualidade | PRD, trade-offs do HLD-001 | Figura qualitativa não vira medição ou promessa de disponibilidade. Cotações toleram atraso; saldo requer consistência local. Redis único não comprova disponibilidade sob partição. |
| §7, p. 7–10: MER/DER e dicionário | HLD-002 FO, HLD-003 MO, HLD-005 BO | Oito estruturas e 51 campos do dicionário preservados como preliminares. `ativo` é referência local, não banco compartilhado. Relações entre serviços são IDs em eventos, sem FK física. Livro continua sem tabela. |
| §8, p. 10–11: padrões | ADR-002 e HLDs | Single writer, outbox, idempotência e domínio puro preservados. State/Strategy/records são candidatos de implementação. Timeout isolado não autoriza liberar reserva; negócio efetivado se recupera para frente. |
| §9, p. 11: estudo | PRD e HLD-001/004 | Liquidez/slippage, DDD, concorrência, transações e telemetria preservados; referências pessoais não viram requisito. |
| §10, p. 11: testes | ADR-004, roadmaps P03/P06/V01 | Unidade, propriedades, integração, arquitetura e carga continuam necessários conforme comportamento. XLSX via API é acréscimo aprovado. jqwik, Testcontainers e k6 citados no PDF não são adoção automática. |
| §11–12, p. 12: riscos e melhorias | HLD-001 e seções de recuperação locais | Concorrência, duplicação, GC e ativo quente tratados como riscos. HMAC/mTLS/Argon2, jobs, arquivamento e ferramentas de profiling são alternativas, não decisões aceitas. |
| §13, p. 13: 12 semanas | Roadmap vigente | Ordem/cronograma substituídos por tarefas com dependências; não há compromisso de prazo herdado. Alertas e demonstração final agora explícitos em V01. |
| §14, p. 13–14: código de referência | HLDs, ADR-001 e [README](../../README.md) | Caminhos/classes de negócio são exemplos futuros. Módulo `comum` não é obrigatório; não antecipar biblioteca compartilhada. GIF exige fluxo real; diagramas atuais não o simulam. |

## Achados e encaminhamento

| Classificação | Evidência / impacto | Ação | Responsável / estado |
| --- | --- | --- | --- |
| `[DOC-GAP]` | PDF RF03 diz último preço e RF06 VWAP do dia; PRD/roadmap usavam referência e VWAP sem qualificador | Explicitar intenção original, preservando decisões de bootstrap e calendário pendentes | Autor desta revisão; corrigido no texto |
| `[DOC-GAP]` | PDF §1 menciona tabela de fidelidade, mas só enumera exclusões; PRD não tinha tabela | Consolidar simplificações no PRD, incluindo stops no ME | Autor desta revisão; corrigido |
| `[DOC-GAP]` | PDF §13/14 pede alertas e apresentação; V01 não explicitava sua entrega | Acrescentar aceite futuro proporcional na V01, sem limiares inventados | Autor desta revisão; corrigido; implementação V01 planejada |
| `[DOC-GAP]` | HLD tinha desenho de destino, mas README não permitia comparar estrutura atual e futura | Acrescentar C1/C2/C3 sustentados por fontes e mapa compacto do repositório | Autor desta revisão; documentação acrescentada |
| Limitação herdada | PDF §7 não define participantes do negócio, escalas e estados suficientes para liquidação; HLD-005 já registra lacunas | Refinar antes das respectivas migrações; não copiar DER como schema aprovado | RF02–RF08; pendente no roadmap/HLDs |
| Diferença decidida | Stack, banco do ME, estrutura do código e testes divergem do PDF | Manter ADRs vigentes; não retornar à proposta histórica | ADR-001/002/004/005; documentado |
| Diferença operacional | PDF §14 centraliza três sinais no Collector; configuração atual exporta logs/traces via Collector e Prometheus coleta métricas diretamente | Explicitar topologia vigente no HLD-001 e C2; eventual centralização requer avaliação própria | Autor desta revisão; descrição corrigida, sem mudança operacional |

Não foi identificada nova violação de ADR nesta inspeção estática; não constitui auditoria integral do software nem comprovação de RNFs.

## Reduzir ou ampliar os documentos

Manter um PRD, cinco HLDs e os ADRs: representam responsabilidades diferentes. Evitar outro PROJETO, transcrição do PDF, HLD duplicado ou inventário completo no README. O relatório presente registra a comparação de fontes; não é nova especificação.

Reduzir repetição futura: stack/versões transversais podem permanecer em HLD-001/libs, enquanto cada HLD mantém somente diferenças locais e links; conservar ali portas, invariantes e dados específicos. Não remover relatórios históricos de evidência como se fossem duplicação normativa. Não alterar texto de skills sem atualizar seus hashes.

Ampliar sobretudo a visualização: [catálogo C4](../c4/README.md), fontes editáveis e imagens locais no README. C3 de negociação e C4 de classes de negócio ficam para quando houver implementação. MER/DER futuros devem evoluir nos HLDs de cada proprietário após as decisões de dados; o dicionário preliminar já atende a fase atual. Não há necessidade concreta de novo PRD, ADR, FDD ou LLD nesta revisão.

## Verificação

Mudança documental, sem alteração funcional: dispensa de nova planilha pelo ADR-004. Verificações e pareceres independentes desta entrega estão em [validação documental](arquitetura-pdf-validacao.md). A arquitetura permanece regida pelos mesmos ADRs; nenhum schema ou contrato de evento foi aprovado por esta comparação.
