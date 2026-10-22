# ADR-014 — Cotação real no front office a partir dos eventos do matching (RF06)

- Data: 2026-10-22.
- Estado: **proposta com implementação RF06a em validação para revisão**; namespace principal real e base do túnel real foram adotados como proposta revisável, sem atribuição de aceite ao usuário. RF06a inclui backend e planilha; RF06b inclui tela e E2E específico.
- Origem: desenho local encontrado na retomada da [RF06](../roadmap.md#rf06--formação-e-atualização-do-mercado), confrontado com cinco pareceres independentes em 2026-10-22. As atribuições anteriores de aceite não foram recuperadas da memória e não constituem aceite comprovado.
- Emenda: [ADR-009](ADR-009-projecao-mercado-fo.md) (namespace principal e origem) e [ADR-011](ADR-011-validacao-preco-ordens.md) (base do túnel passa a ser o último negócio real).

## Contexto

A RF01 entregou consulta de mercado sobre snapshots sintéticos no PostgreSQL do FO (ADR-009), e a RF05 entregou o matching com o tópico `matching.eventos.v1` (ADR-013). A RF06 pede último preço, bid/ask, profundidade e VWAP do dia atualizados por negócio e por mudanças de ofertas sem negócio. A base do túnel (ADR-011) deveria ser o último negócio da sessão, mas `FonteUltimoPrecoSemNegocios` sempre devolvia vazio.

Refinamento: brief comum e cinco pareceres medium independentes (arquitetura, QA, segurança, operação, viabilidade), todos **SEGUIR COM RESSALVAS**. Consensos: os eventos atuais bastam (`OFERTA_NO_LIVRO`, `NEGOCIO` com o lado passivo, `ORDEM_ENCERRADA` de execução, IOC/FOK, OCO e expiração DIA, `ORDEM_REDUZIDA`, `SESSAO_ENCERRADA`), sem evento novo do ME; projeção no PostgreSQL do FO, não Redis (evicção LRU, sem idempotência); VWAP em decimal exato; cancelamento pelo usuário só existe na RF10 (o aceite usa IOC, OCO e expiração); fatiar em backend e tela. Alternativa avaliada: projetar na mesma transação do consumidor de ordens ou num consumidor próprio; a retomada escolheu consumidor próprio para permitir replay independente.

## Decisões

| Tema | Decisão |
| --- | --- |
| Namespace principal (proposta) | **Só dados reais do matching.** Preparação sintética no namespace principal é recusada com `409 NAMESPACE_REAL`; namespaces UUID continuam sintéticos (`mercado.xlsx`). A demonstração passa a usar o robô formador (RF05d). |
| Base do túnel (proposta) | **Ligada na RF06**: `FonteUltimoPreco` lê o último negócio real da sessão na projeção. Defasagem da projeção frente ao ME e o risco do Kafka PLAINTEXT alcançando a base são limitações explícitas desta proposta. |
| Tela (proposta RF06b) | Polling de ~1 s com recuo em erro até a RF09 (RF06b), com limite de taxa no Nginx em `/ativos`. |
| Encerramento (proposta) | Após `SESSAO_ENCERRADA`, livro vazio; último, volume e VWAP da sessão permanecem visíveis e marcados `defasado`; nova sessão começa do zero ao receber seu primeiro efeito de mercado. |
| Armazenamento | PostgreSQL do FO (ADR-009 mantido); Redis só como cache futuro medido. O roadmap que dizia "projeção Redis" foi corrigido. |
| Consumidor | **Próprio**, grupo `front-office-cotacao`, `earliest`, um registro por vez, com o mesmo parser estrito `EnvelopeMatching` e as mesmas guardas de versão, origem e allowlists. Deduplicação própria (`cotacao_evento`, id + assinatura, e `cotacao_negocio`, identidade + assinatura), porque a tabela `matching_evento` já marca eventos aplicados às ordens. Motivos: a projeção nasce completa pelo replay do tópico (retenção infinita, uma sessão por log), sem ler o banco do ME; e uma quarentena da cotação não tira o FO de prontidão nem bloqueia a projeção de ordens — só marca o mercado como `defasado`. Custo: mais um consumidor concorrendo pelo pool e duas tabelas próprias de deduplicação. |
| Modelo | `cotacao_oferta` (ordem → ticker, sessão, lado, preço, restante) e `cotacao_sessao` (ticker, sessão → último, Σq, Σp·q em NUMERIC, revisão, instante, encerrada). Helper puro traduz cada evento em mutações; o adaptador aplica as mutações e a deduplicação na mesma transação. A revisão só sobe quando algo muda. |
| Regras do livro | `OFERTA_NO_LIVRO` grava o restante da ordem; `NEGOCIO` consome a quantidade da ordem passiva (oposta ao agressor) e atualiza último, Σq e Σp·q; `ORDEM_ENCERRADA` remove a ordem; `ORDEM_REDUZIDA` ajusta o restante se a ordem estiver no livro (stops pendentes nunca estão); `SESSAO_ENCERRADA` esvazia o livro do ativo/data e marca a sessão encerrada. |
| VWAP | Σp·q / Σq da sessão do evento (`dataSessao`, America/Sao_Paulo), quatro casas HALF_EVEN só na exposição; acumuladores exatos. `null` sem negócio. Reentrega do mesmo id não altera nada. |
| Defasado | Para a origem `MATCHING`: sessão anterior ao dia atual de São Paulo, sessão encerrada ou consumidor não em dia (antes de alcançar o fim do tópico na partida, ou em quarentena). Tempo sem negócio não é defasagem. Snapshots sintéticos mantêm a regra do ADR-009. |
| Contrato | Aditivo: `vwap` no resumo do ativo e `origem` `MATCHING` ou `PREPARACAO_SINTETICA`; `preparadoEm` passa a ser o instante de aplicação da última mudança na projeção, inclusive em replay; não representa o instante original do negócio. Profundidade só agregada por preço (quantidade e número de ofertas), sem conta, ordem ou intenção. |

## Alternativas

- Evento `LIVRO_ATUALIZADO` (v5) publicado pelo ME num tópico compactado: recuperação trivial, mas exige implantação em duas fases (tipo desconhecido quarentena FO e MO) e altera o ADR-013 sem necessidade.
- Redis: menor latência, mas evicção e ausência de idempotência transacional; recusado por coerência com o ADR-009.
- Mesma transação do consumidor de ordens: atômico com a projeção de ordens e sem conexão extra, mas a projeção nasceria incompleta para eventos já confirmados e uma falha da cotação bloquearia as ordens.
- Sintético e real convivendo no namespace principal com campo `origem`: mais simples para a CI, não escolhido nesta proposta (mistura fontes e permitiria que a preparação influenciasse a base do túnel).

## Consequências

- `preparar-demo` e o E2E deixam de usar o namespace principal sintético; a tela de demonstração depende de negócios reais (robô e scripts via API).
- A base do túnel passa a acompanhar o mercado: ordens fora de ±10% do último negócio real são recusadas na RF03.
- Reconstrução: reconstruir conjuntamente `cotacao_evento`, `cotacao_negocio`, `cotacao_oferta` e `cotacao_sessao` e reposicionar o grupo `front-office-cotacao` no início reconstrói a projeção (procedimento operacional, não rotina).
- O replay cresce com a sessão; retenção e compactação continuam sem decisão (uma sessão por log no laboratório).
- Kafka PLAINTEXT: um `NEGOCIO` forjado e coerente agora também desloca último, VWAP e base do túnel; a reserva continua protegida pelo teto do ME.

## Revisão

Revisar ao introduzir múltiplas sessões por log, autenticação de eventos, cache medido ou WebSocket (RF09).

## Migração e recuperação

V10 é expansão aditiva, sem alterar dados de ordens, reservas ou snapshots existentes. Flyway aplica as quatro tabelas e o índice na transação PostgreSQL; falha aborta a expansão. A versão anterior continua podendo iniciar com as tabelas adicionais; em regressão, restaurar a imagem anterior e preservar as tabelas, corrigindo por nova migração, sem editar migração já aplicada. Reconstrução é operação explícita: parar somente o FO/consumidor de cotação, obter cópia recuperável se necessário, limpar conjuntamente as quatro tabelas próprias e reposicionar exclusivamente o grupo `front-office-cotacao` no início do tópico, reiniciar e aguardar recuperação. Nunca executar limpeza parcial ou apagar volumes por rotina. Este texto não autoriza nem declara executada essa operação destrutiva.
