# ADR-017 — Trajetória durável e avisos de atualização

- Data: 2026-10-24.
- Estado: **proposta técnica em implementação pela RF09**; não representa aceite explícito do usuário deste ADR.
- Autorização: requisito RF09 e ADRs 001/002/003/009/010/012–016 vigentes; usuário solicitou prosseguir após integrar a RF08. Nenhuma nova regra financeira, fronteira de serviço ou autenticação real é adotada.
- Fontes: [PRD](../prds/PRD-001-simulador-prospero.md), [roadmap](../roadmap.md#rf09--tempo-real-e-trajetória-da-ordem), [FO](../hlds/HLD-002-front-office.md), [BO](../hlds/HLD-005-back-office.md).

## Contexto e alternativas

O FO conhece recebimento, risco e execução; essas projeções não provam que o BO contabilizou o negócio. Jaeger é efêmero e não pode armazenar a linha do tempo. O navegador não recebe credenciais dos serviços. Atualização assíncrona deve sobreviver a reinício e desconexão sem repetir ordens.

| Alternativa | Consequência |
| --- | --- |
| Usar traces como histórico | Perde fatos após reinício/expurgo da telemetria; rejeitada |
| FO consultar BO ou seu banco | Viola autonomia e integração de negócio por eventos; rejeitada |
| Transmitir estado financeiro completo pelo socket com replay próprio | Duplica contratos de recuperação, buffers e autorização; desnecessário |
| **FO persiste fatos, BO confirma por outbox; socket invalida e HTTP recupera** | Reutiliza autoridade financeira, Kafka e APIs; escolhida tecnicamente para a RF09 |

## Trajetória e ordenação

O PostgreSQL do FO guarda marcos por ordem, conta, evento e origem, com assinatura de conteúdo. Recebimento FO confirma com ordem/outbox; resultado MO confirma com aplicação do risco; marcos ME confirmam com a projeção e sua deduplicação. Decisão de risco tardia pode ser registrada sem regredir a ordem. Reentrega idêntica não cria marco/revisão; identidade divergente é inconsistente. Instante de origem pode ser ausente no legado; nunca é inventado.

Uma linha de revisão por conta é bloqueada/atualizada na transação de inclusão. Contas múltiplas são bloqueadas em ordem fixa, depois dos locks financeiros e antes de inserir marcos; não há nova escrita financeira depois desses locks. Isso serializa a publicação das revisões por commit. `BIGSERIAL` livre não é watermark de commit. A sequência é textual, local e de **observação**, não uma ordem global causal entre tópicos. Persistir origem, evento, causação, negócio, instante de ocorrência, instante de observação, quantidade textual e motivo enumerado quando houver.

Legado: ordens existentes e seus estados continuam consultáveis. `historicoCompleto=false` quando falta o marco original de recebimento; a API informa o início da disponibilidade. Não fabricar marcos/horários, reconstruir risco já expirado, apagar volumes nem fazer backfill automático. A retenção da trajetória é integral no laboratório, sem novo expurgo.

## Confirmação BO → FO

Novo tópico `acompanhamento.eventos.v1`, três partições, chave ticker, retenção integral; produtor BO e consumidor próprio do FO. `NEGOCIO_CONTABILIZADO` v1 nasce na **mesma transação** de posição/fato/razão D0/checkpoint BO, com outbox existente. Não basta consumir o evento do ME. O marco não altera estado, reserva, caixa ou posição no FO.

Envelope estrito, até 16 KiB:

- raiz `id,tipo,versao,origem,causacao,ocorridoEm,traceparent,dados`;
- `id=UUID.nameUUIDFromBytes(UTF-8("acompanhamento:" + negocio))`, `tipo=NEGOCIO_CONTABILIZADO`, `versao=1`, `origem=back-office`, `causacao` UUID do evento ME;
- `ocorridoEm` do registro BO; `traceparent` W3C ou null;
- dados `negocio,ticker,quantidade,ordemCompra,contaCompra,ordemVenda,contaVenda`; quantidade inteira positiva **textual**, UUIDs canônicos e contas/ativos conhecidos;
- FO verifica correspondência das ordens/contas/ativo no próprio banco; deduplica id/assinatura e grava somente os marcos de cada ordem própria. Payload interno nunca é exposto pela API.

A confirmação BO também invalida as posições de todas as quatro contas: cotação de um negócio pode alterar PnL de quem não participou. O aviso não revela participante ou valor; usa somente a revisão da conta. Os detalhes D+2 continuam na razão/histórico RF08; a trajetória RF09 identifica o processamento contábil D0 pelos quatro serviços.

## HTTP e WebSocket

`GET /api/v1/ordens/{id}/trajetoria` no FO usa audiência FO e `ordens:leitura`; conta vem do JWT, ordem alheia e inexistente são 404 idênticos. Parâmetros `cursor` e `limite` (padrão20, máximo100). Resposta: `ordem,conta,revisao,historicoCompleto,disponivelDesde,eventos,proximoCursor`. Eventos: `sequencia,origem,tipo,evento,causacao,negocio,ocorridoEm,observadoEm,quantidade,motivo,traceId`; campos opcionais null. Origem é `front-office`, `middle-office`, `matching-engine` ou `back-office`. Tipos FO `ORDEM_RECEBIDA`; MO `RISCO_RESERVADO`/`RISCO_REJEITADO`; ME nomes dos fatos existentes; BO `NEGOCIO_CONTABILIZADO`. Cursor opaco vinculado a conta/ordem/posição, nunca autoridade; paginação crescente por revisão confirmada. `no-store`, entrada inválida400, projeção em quarentena503; estado financeiro atual continua na API de ordens.

`/api/v1/atualizacoes` é WebSocket somente leitura no FO, pela mesma identidade/escopo. Extensão `quarkus-websockets-next` do BOM3.40.1. O proxy expõe somente `/servicos/front-office/api/v1/atualizacoes`, injeta o token de ordens no upgrade exato e descarta Authorization recebido. Sem token em URL, frame, subprotocolo ou JavaScript. Handshake exige Origin HTTP local igual ao Host público; backend exige Origin local e JWT válido. Rotas genéricas não encaminham upgrade autenticado. Não se adotam cookies/sessões ou autenticação nova.

Frames JSON v1: `{versao:1,tipo:"RESSINCRONIZAR",revisao:"N"}` ao abrir e `{versao:1,tipo:"ATUALIZACAO",revisao:"N"}` quando mudar a revisão da conta. São avisos; não contêm estado financeiro nem garantem replay. Uma consulta periódica compartilhada lê somente as quatro revisões confirmadas, no máximo uma vez por segundo, independentemente do número de clientes; nenhuma conexão SQL fica presa ao socket.

Limites iniciais: oito conexões por conta,32 globais; no máximo um envio em curso por sessão, prazo de envio3s; cliente lento é encerrado e recupera por HTTP. Mensagem recebida é recusada/encerra; quadro/mensagem até1024bytes, sem compressão; ping15s e sessão encerrada ao expirar JWT. Métricas têm labels finitos, sem conta, ordem, preço ou traceId.

Angular abre o canal e **refaz HTTP ao receber RESSINCRONIZAR**, cobrindo a janela snapshot/assinatura. Mudança durante GET fica pendente para uma consulta posterior, sem sobreposição. Mensagens duplicadas/antigas não regridem estado; reconexão usa recuo1/2/4/8s com jitter limitado. Fecha ao ocultar/destruir e reconecta/reconcilia ao voltar. Falha mantém snapshot com aviso e permite atualização manual; consultas periódicas existentes podem servir de recuperação, sem apresentar socket aberto como prontidão financeira.

## Trace e recuperação

Completar spans consumidores nos serviços que ainda não os produzem, extrair contexto W3C e persistir contexto BO para mensagens posteriores, inclusive D+2. HTTP FO → risco MO → matching ME → contabilização BO deve exportar spans reais no mesmo trace da agressora. Passiva mantém vínculo por ordem/negócio; não herda identidade/autorização da agressora. Correlacionar por vínculos, sem afirmar que toda execução futura de uma passiva pertence ao trace original de seu envio.

Telemetria não é autoridade nem armazenamento da trajetória. Falha do coletor não altera dinheiro ou apaga fatos. Guardas de envelope/hash não autenticam Kafka PLAINTEXT; risco herdado dos ADRs anteriores permanece. Sem novo dashboard/metas RNF07 por inferência; os quatro dashboards completos ficam na V01.

Migrações FO/BO aditivas, sem edição de versões aplicadas. Retorno de imagem preserva novas tabelas/colunas; sem escrita de novas notificações, o histórico passa a ficar incompleto até correção para frente. Recuperação de schema é nova migração ou restauração de backup privado compatível; não prometer restauração executada. Consumidor FO confirma offset depois do commit, repete falhas técnicas e sinaliza inconsistência sem inventar confirmação BO.

## Critérios de verificação

- Sucesso/parcial/rejeição/OCO/Stop/passiva; fatos reais e sem contraparte; risco tardio sem regressão.
- PostgreSQL: marco/estado/outbox atômicos, rollback, duplicata, identidade divergente, commits invertidos e cursor sem perda; migração com ordens anteriores.
- HTTP: autorização da conta,404 idêntico, limites/cursor/no-store/503; `trajetoria.xlsx` por APIs, duas execuções.
- WS/proxy real: JWT/Origin/Host, token ausente do navegador, expiração, limites/cliente lento, duplicação/reordenação, corrida GET/WS e reconexão.
- BO indisponível mostra ausência de confirmação; retomada preserva banco/log e não duplica marco/efeito.
- Jaeger: trace conhecido contém processamento FO/MO/ME/BO; indisponibilidade de telemetria não muda estado financeiro.
- E2E desktop/mobile após abrir a tela, mudanças sem clique e capturas reais. Revisão, QA e segurança medium independentes; resultados/SHA/limites no PR.

Referência de API: [WebSockets Next3.40](https://quarkus.io/version/3.40/guides/websockets-next-reference/). Os limites são orçamento inicial de laboratório, não capacidade medida.
