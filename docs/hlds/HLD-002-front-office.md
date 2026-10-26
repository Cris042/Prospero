# HLD-002 — Front office

Data: 2026-10-17. Desenho vigente e planejado do contexto, sem nova decisão de produto.

## Responsabilidade e estado

Contexto de negociação/entrada do investidor: RF01/RF02, participação em RF06/RF09/RF10 e coordenação da RF04/RF05. Implementa consulta de mercado, registro/consulta idempotente de ordens, validação lote/tick/túnel e SAGA de risco FO ↔ MO. A RF05b acrescenta o consumidor de execuções e a projeção de progresso das ordens; a RF06a acrescenta a projeção real de mercado (integrada no PR #37). Trajetória durável e WebSocket estão implementados nas fatias RF09a/RF09b, ainda em validação nos PRs #49/#50. Estado de validação/PR no roadmap.

Futuro: receber/consultar ordens, persistir estados, orquestrar validação/reserva/execução e expor projeções. Risco pertence ao MO; livro ao ME; contabilidade ao BO. Angular apresenta estados sem coordenar SAGA.

## Stack e organização

Java 26, Quarkus 3.40.1, Maven Wrapper, REST Jackson/Bean Validation, JPA/Hibernate, PostgreSQL 16 e Flyway; cliente Kafka, SmallRye OpenAPI, Health, Micrometer e OpenTelemetry. Versões resolvidas/imagens e overrides: [libs](../libs.md) e ADR-005. Build e imagem próprios, sem artefato compartilhado entre serviços. Não há dependência de implementação de outro contexto.

Organização por funcionalidade sob `br.prospero.fo`: REST → Actor; Actor coordena domínio/Helpers puros e repositórios JPA separados. Adaptadores Kafka futuros chamam casos de uso sem passar pelo REST. Entidades JPA não são DTOs nem domínio. Pacotes vazios não são componentes de negócio implementados.

## Interfaces e implantação atuais

`GET /api/v1/sistema` retorna identidade técnica. Porta externa `8081` em loopback → `8080` no container; `/q/health/ready`, `/q/openapi`, `/q/swagger-ui/` e `/q/metrics` são técnicos. Configuração usa variáveis locais fora do Git. Banco/container exclusivo `postgres-front-office`, banco `prospero_fo`, rede de dados própria. Não acessar tabelas de outro serviço.

`src/main/resources/db/migration/V1__identificar_schema.sql` identifica o schema; as migrações seguintes acrescentam mercado, ordens, outbox e projeção de matching descritos abaixo. Flyway mantém o histórico das versões aplicadas. Build com JDK26: `./mvnw verify` no diretório do serviço; operação pela skill subir-sistema. Processo pode iniciar sem outro MS Java, mas depende de sua infraestrutura para ficar pronto; não confundir autonomia com conclusão de negócio sem participantes.

## Fluxo futuro e dados

Receber DTO → validar estrutura → Actor registra ordem e intenção de publicação na mesma transação local → publisher recuperável Kafka → respostas/eventos avançam a máquina de estados persistida. RF02a implementada conforme [ADR-010](../adrs/ADR-010-ordens-contas-idempotencia.md): `POST/GET /api/v1/ordens` e `/api/v1/ordens-oco`, `202` com `Location`, idempotência `(conta, X-Idempotency-Key)` com hash na mesma transação, contas sintéticas por `sub`, estado único `RECEBIDA`. Desde a RF04a a outbox recebe `AVALIAR_RISCO` na mesma transação; o relay publica em `risco.comandos.v1` (chave = conta) e o consumidor aplica o resultado só a ordens `RECEBIDA` da mesma conta e intenção ([ADR-012](../adrs/ADR-012-risco-reservas-saga.md)).

RF01 implementada conforme [ADR-009](../adrs/ADR-009-projecao-mercado-fo.md): projeção de leitura no PostgreSQL do FO (`snapshot_mercado`, uma linha JSONB por namespace/ticker), consulta `GET /api/v1/ativos` e `/api/v1/ativos/{ticker}/livro` e preparação sintética local por namespace, todas com JWT. Redis permanece opção de cache futuro, sem uso atual. RF06a projeta negócios e ofertas no mesmo PostgreSQL, em tabelas próprias (seção abaixo). Negócio não usa HTTP entre MS. Nomes de comandos, envelopes e tópicos dependem do catálogo transversal do HLD-001.

RF05b: `AVALIAR_RISCO` versão2 inclui sessão e validade. `matching.eventos.v1` avança ordens para `ABERTA/PARCIALMENTE_EXECUTADA/EXECUTADA/CANCELADA` ou rejeição do ME, com quantidades executada/cancelada e motivo terminal no DTO de consulta. Não exige receber `RESULTADO_RISCO` primeiro: aplica matching desde `RECEBIDA`, e o resultado tardio não regride progresso. Os dois lados de negócio e deduplicação confirmam juntos. `V6__projetar_matching.sql` preserva ordens antigas, acrescenta progresso e identidade indexada da admissão das novas simples, além dos fatos de deduplicação. Correlação, conta, intenção, ativo, sessão, preço/quantidade e transições são validados antes dos efeitos; inconsistência bloqueia a sequência e a prontidão financeira sem confirmar offset. Contratos/replay e limites no [HLD-004](HLD-004-matching-engine.md#contrato-de-integração-rf05b).

## Recuperação, riscos e pendências

Persistir SAGA, timeout e próximos passos; definir janela de publicação, reentrega e reinício sem duplicar submissão. Antes de compensar, conhecer resultado efetivo: negócio executado exige recuperação para frente, sem desfazê-lo. Definir concorrência de cancelamento/execução/timeout e idempotência dos comandos.

Pendentes: correlação ordem/outbox ausente na tabela histórica; validação da apresentação contínua na RF06b. As transições de risco e execução estão descritas acima e a espera/ativação de stops na RF05c.2 abaixo. Contas sintéticas e escopos definidos no ADR-010; identidade real permanece futura.

Aceite futuro: duplicação, reentrega, indisponibilidade e consulta de estados consistentes, WebSocket/reconexão e planilha com polling limitado. [Roadmap FO](../../front-office/ROADMAP.md); RNFs especialmente RNF05–RNF08. Não há meta própria nova de latência.

## Modelo de dados de referência

Dicionário migrado da proposta histórica. **Superado nas tabelas já implementadas**: `ativo` (V2, ADR-009) e `conta`, `ordem`, `idempotencia_envio`, `limite_ordens` (V3, ADR-010) e a base do túnel `preco_base_tunel`/`origem_base` com OMEGA3 em lote 100/tick 0,05 (V4, ADR-011) usam `NUMERIC(12,2)` para preços; as migrações Flyway são a fonte. O restante é planejado e incompleto, não schema aprovado. Tipos bigint não definem escala financeira; ausência de marcação não decide nulabilidade/índice/constraint. Referências a IDs externos não criam FK entre bancos. `ativo` representa referência local a distribuir por contrato, sem tabela compartilhada. Refinar antes de migrações.

### `ativo` — referência preliminar
| Coluna | Tipo | Chave |
| --- | --- | --- |
| `ticker` | `varchar(12)` | PK |
| `tick` | `bigint` | — |
| `lote_minimo` | `bigint` | — |
| `tunel_pct` | `int` | — |
| `negociavel` | `boolean` | — |

### `ordem` — referência preliminar
| Coluna | Tipo | Chave |
| --- | --- | --- |
| `id` | `uuid` | PK |
| `conta_id` | `varchar(32)` | — |
| `ativo` | `varchar(12)` | FK para referência local de ativo |
| `lado` | `char(1)` | — |
| `tipo` | `varchar(12)` | — |
| `validade` | `varchar(4)` | — |
| `quantidade` | `bigint` | — |
| `executada` | `bigint` | — |
| `preco_limite` | `bigint` | — |
| `gatilho` | `bigint` | — |
| `oco_par` | `uuid` | — |
| `estado` | `varchar(20)` | — |
| `versao` | `int` | — |

### `outbox` — referência preliminar
| Coluna | Tipo | Chave |
| --- | --- | --- |
| `id` | `bigserial` | PK |
| `topico` | `varchar(64)` | — |
| `chave` | `varchar(64)` | — |
| `tipo` | `varchar(48)` | — |
| `payload` | `jsonb` | — |
| `publicado_em` | `timestamptz` | — |

## Histórico de ordens — conservação planejada

A avaliação de arquivamento é válida com condições: FO continua proprietário do histórico de ordens/SAGA e não consulta tabelas BO. Mover somente ordens elegíveis encerradas, preservando pendências, correlações, reentrega/deduplicação e recuperação. Não há tabela financeira ou volume medido para adotar partições/arquivo frio hoje.

A política proposta no [HLD-005](HLD-005-back-office.md#histórico-frio-e-fechamento-por-conjuntos--avaliação-para-rf08) exige arquivo verificado/restaurável, retenção explícita, controle de acesso, consulta por conta/período paginada e falha/recuperação declaradas. Não apagar a verdade de negócio nem responder vazio porque o dado saiu da área quente; restauração temporária versus leitura de arquivo permanecem alternativas. Estado corrente de uma ordem pode mudar conforme seu ciclo, mas arquivamento não autoriza alterar silenciosamente negócios/razão ou apagar sua trajetória.

## Segurança, observabilidade e testes

Seguir ADR-003 para DTOs, versão, Swagger, status e erros; contratos de negócio ainda precisam de definição. Autenticação e autorização por conta/papel são pendências, sem RBAC presumido. Não registrar credenciais ou payloads sensíveis. Entradas exigem limites e invariantes; o endpoint técnico não comprova proteção do fluxo financeiro.

Fundação: saúde de banco/Kafka, métricas técnicas, logs com correlação e exportação OTLP. Não afirmar trace financeiro completo, métricas de domínio ou capacidade comprovada. Testes atuais usam JUnit/REST Assured/ArchUnit; perfil de contrato desativa banco/Kafka, smoke Compose exercita dependências reais. Casos futuros via API/planilha conforme ADR-004 complementam invariantes internas e testes de concorrência/recuperação.

## Decisões e rastreabilidade

[ADR-001](../adrs/ADR-001-arquitetura-backend.md) define fronteiras/backend; [ADR-002](../adrs/ADR-002-padroes-dominio.md), padrões; [ADR-003](../adrs/ADR-003-contratos-api.md), contratos; [ADR-004](../adrs/ADR-004-testes-planilha.md), testes; [ADR-005](../adrs/ADR-005-versoes-fundacao.md), stack. [PRD-001](../prds/PRD-001-simulador-prospero.md) é a fonte dos requisitos. Este HLD detalha o [ecossistema](HLD-001-arquitetura-prospero.md), sem alterar decisões.

## Projeção de stops — RF05c.2

`V8__progresso_oco.sql` (RF05c.3b1) torna a unicidade da admissão `(admissao_matching, papel_oco)`, acrescenta `quantidade_reduzida` e amplia `ordem_progresso`; novas pernas OCO recebem a admissão do grupo. A projeção v3 abre o TP, mantém o SL em espera, reduz só a quantidade vigente do SL e encerra pernas com `CANCELADA_OCO`; v3 exatamente quando há perna OCO envolvida. Na RF05c.3b2 `OrdemResponseDTO` ganha `quantidadeReduzida` (aditivo) e a boleta descreve a regra do OCO, mostrando a redução do Stop Loss e o encerramento `CANCELADA_OCO`. RF05c.4a: a projeção aceita a expiração DIA v4 (inclusive stop pendente e perna OCO) como `CANCELADA{EXPIRADA_DIA}` e as rejeições por sessão encerrada; o marco v4 confere a causação e é só deduplicado.

`V7__estado_aguardando_gatilho.sql` amplia a constraint de `ordem.estado` com `AGUARDANDO_GATILHO`, sem transformar ordens antigas. Aceite de novo stop põe a ordem em espera; `STOP_PENDENTE` confirma o gatilho original; `STOP_ACIONADO` valida conversão e abre execução (`ABERTA`). Tipo/validade públicos permanecem Stop/Stop Limit DIA. Negócio/oferta antes de acionamento é incoerente; resultado de risco tardio continua alterando somente `RECEBIDA`. Consulta/boleta distinguem espera e execução sem nova API. Market/Limit v1 continuam compatíveis. Eventos v2 validam `causacao` raiz e `correlacao` alvo/agressor, conforme [ADR-013](../adrs/ADR-013-matching-execucao.md#contrato-da-integração-de-stops--rf05c2-2026-10-20). Na RF05c.2, OCO e fechamento integrado ficavam fora (tratados depois na RF05c.3/4); migração e serviços exigem atualização coordenada antes da emissão v2.


## Cotação real — RF06a

O [ADR-014](../adrs/ADR-014-cotacao-real-fo.md), ainda proposto para revisão, descreve a implementação em validação. O consumidor exclusivo `front-office-cotacao` lê `matching.eventos.v1` desde o início com parser estrito e commit manual após a transação. `cotacao_evento` e `cotacao_negocio` deduplicam identidades distintas; `cotacao_oferta` representa somente passivas e `cotacao_sessao` acumula último, quantidade e financeiro exatos. Locks transacionais por evento/ativo serializam reentrega e alterações; divergência de assinatura ou de saldo/preço/lado/sessão põe o consumidor em quarentena, sem retirar prontidão das ordens.

A consulta usa uma instrução SQL por ativo para obter a última sessão, indicadores e cinco níveis por lado, com totais do livro inteiro. Não carrega ofertas individualmente na aplicação nem expõe contas/ordens. O namespace principal consulta somente essa projeção; UUIDs diferentes do nulo continuam sintéticos e a preparação/limpeza do principal retorna `409 NAMESPACE_REAL`. `origem=MATCHING` indica a fonte mesmo em `SEM_DADOS`; `vwap` é textual com quatro casas HALF_EVEN e null sem negócio. Volumes sem negócio também são null na exposição. Revisão sobe uma vez por evento com efeito; `preparadoEm` é instante de aplicação da última mudança, inclusive no replay.

Defasagem real significa sessão anterior ao dia de São Paulo, sessão encerrada ou consumidor atrasado/em quarentena. O ciclo compara posições persistidas/confirmadas com os fins de todas as partições atribuídas, invalida em falha/rebalance e expira a verificação em dez segundos. Não interpreta silêncio de negociação como atraso. Isso pressupõe a instância única do Compose; escalar o FO exige estado global de frescor. As métricas `prospero.cotacao.em_dia`, `.eventos`, `.falhas` e `.quarentena` têm cardinalidade limitada; o log de quarentena traz partição, offset e motivo controlado, sem payload.

`FonteUltimoPrecoCotacao` atende a porta das ordens lendo exclusivamente último real por ticker/sessão; sintéticos não alimentam o túnel. Sem negócio na sessão solicitada, ADR-011 mantém referência. Uma projeção defasada pode atrasar a base; Kafka PLAINTEXT continua permitindo forjar fatos coerentes e agora alcança a base do túnel. Esses riscos estão explícitos na proposta, sem aceite do usuário presumido.

V10 é aditiva e a versão anterior pode conviver com as tabelas extras. Recuperação por correção aditiva ou retorno da imagem preserva os dados; reconstrução conjunta das quatro tabelas e reposicionamento exclusivo do grupo exigem operação explícita, descrita no ADR-014. Tabelas e log crescem com a sessão; nenhuma retenção nova ou meta de capacidade foi comprovada. O pool de quatro conexões permanece; uma nova thread não reserva conexão permanentemente.

O workbook `cotacao.xlsx` usa ordens e carteiras via APIs, negócios a 20,00 em BETA3, profundidade/parciais/IOC, VWAP e repetição; exige a sessão de laboratório sem negócio BETA3 anterior em outro preço. A fixture histórica da interface usa UUID próprio via API e o E2E acrescenta somente esse parâmetro aos GETs reais pelo proxy. RF06a integrada no PR #37 entrega o backend e as planilhas. RF06b, em validação, apresenta VWAP textual com quatro casas e polling independente de resumo/livro: próximo GET 1 s após a conclusão, timeout 8 s, recuo 1/2/4/8 s em erro e retorno a 1 s após sucesso. Aba oculta cancela trabalho; visibilidade retoma imediatamente; destruição encerra ambos os ciclos. Manual não interrompe chamada nem antecipa recuo de erro. Cada snapshot mantém seus próprios metadados; falha local preserva dados com aviso, sem alterar a defasagem da fonte. Troca de ticker cancela e remove o livro anterior, sem anúncio acessível a cada tick.

Nginx limita `/ativos` a 5 requisições/s por IP, burst 10, compartilhado entre resumo/livro e abas; 429 local inclui no-store e participa do recuo. POST de ordens mantém sua cota própria. Proxydev restringe leitura GET/HEAD e Host local, com token no servidor, sem cota customizada. Testes de ciclo com tempo controlado e validação HTTP do Nginx verificam o ritmo; nenhuma capacidade, RNF04 ou métrica de domínio nova foi comprovada. Regressão visual permite retornar à imagem frontend anterior sem reconstruir projeção. O E2E deve observar oferta e negócio posteriores à abertura da tela, sem clique; `cotacao.xlsx` continua a prova financeira, sem nova planilha para apresentação conforme ADR-004.

## RF09 — acompanhamento durável e atualização

O [ADR-017](../adrs/ADR-017-trajetoria-tempo-real.md) propõe a implementação da RF09 dentro das fronteiras aceitas. O FO grava marcos de recebimento/risco/matching na transação da projeção e consome confirmação BO recuperável. Revisão por conta serializada pelo commit sustenta paginação crescente e avisos WebSocket; API por ordem/conta é a fonte de recuperação, sem contraparte. Ordens anteriores preservam estado e declaram histórico incompleto. WS não altera finanças; proxy injeta credencial somente na rota exata e valida Origin/Host. Reconexão força nova leitura HTTP e preserva snapshot com aviso em falha. Contratos, limites, legado e critérios no ADR; execução/estado no roadmap.

O canal RF09b usa WebSockets Next; dois executores separam consultas JDBC de timers de expiração, evitando que consulta bloqueada prolongue JWT. A consulta compartilhada lê as quatro revisões uma vez por segundo somente com sessões abertas. Angular compartilha um socket entre ordens, posições e trajetória; avisos agrupados preservam uma consulta ativa e uma pendente. Páginas da trajetória são relidas até o prefixo já carregado, com publicação atômica do snapshot e teto100 na tela. Limites de evidência e status no roadmap.

## V01b — agendamento da outbox após commit

A implementação em validação do [ADR-018](../adrs/ADR-018-publicacao-outbox-apos-commit.md) antecipa o relay depois do commit de o registro da ordem/outbox. Um callback por transação, sem dados/I/O, apenas solicita execução coalescida. Executor único compartilha publicação, fallback de 200 ms e manutenção a cada aproximadamente 5 s por tempo monotônico, inclusive entre lotes; falha impõe cooldown de 200 ms também contra sinais novos. Perda do sinal/reinício continua recuperada pela outbox persistida.

`PROSPERO_OUTBOX_URGENCIA=false` conserva somente polling para comparação/reversão; não desabilita mensageria ou guardas financeiras. Seleção ordenada, lote de 50 / orçamento de 20 s, ack antes de marcação e reentrega idempotente permanecem. Um publicador concreto recebe Producer/EntityManager para verificar essas fronteiras com PostgreSQL e ack controlado; não é domínio ou biblioteca compartilhada. Estado/evidências no roadmap; ganho de latência depende da medição integrada, sem novo aceite financeiro.
