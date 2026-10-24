# HLD-005 — Back office

Data: 2026-10-17. Desenho vigente e planejado do contexto, sem nova decisão de produto.

## Responsabilidade e estado

Contexto de pós-negociação: RF07/RF08 e eventos de trajetória RF09. RF07, integrada pelo PR #42, implementa posição/PnL dos negócios desde a origem do log. RF08b (integrada pelo PR #45) acrescenta fatos econômicos, razão D0, liquidação pendente e consultas; RF08c/d integram crédito no MO e emissor D+2; conciliação e tela são RF08e. A posição parte de zero, não representa a custódia sintética inicial sem custo histórico. Regras confirmadas pelo usuário e limites no [ADR-015](../adrs/ADR-015-projecao-posicoes-bo.md).

## Stack e organização

Java26/Quarkus3.40.1, PostgreSQL16/Flyway, REST/OpenAPI, JPA e cliente Kafka próprios. JWT usa a extensão do mesmo BOM, emissor local compartilhado e audiência BO. REST → Actor coordena domínio/Helpers puros e adaptadores JPA separados; consumidor chama o Actor diretamente. Não há banco alheio, entidades compartilhadas ou biblioteca de implementação entre contextos. [Inventário](../libs.md).

## Interfaces e processamento RF07

`GET /api/v1/posicoes` exige JWT `prospero-back-office`, escopo `posicoes:leitura`, subject CONTA-001..004; nenhum parâmetro/header seleciona outra conta. Retorna os cinco ativos da conta, quantidade e valores textuais, médio ausente na zeragem, realizado acumulado e não realizado por último negócio. `Cache-Control: no-store`; 503 Problem Details durante bootstrap/lag/quarentena. Cotação ausente, de outra sessão ou encerrada é marcada como defasada; tempo sem negócio sozinho não define defasagem.

Grupo próprio `back-office-posicoes` lê `matching.eventos.v1` desde origem comprovada, chave por ativo, três partições e uma sessão/log. Envelope limitado/estrito, correlação/causação, tick e lote são conferidos. Negócio identifica os dois participantes. A transação local confirma deduplicação de evento/negócio, coordenada/hash de transporte, duas posições, cotação e checkpoint; repetição idêntica é inócua, divergente bloqueia. Autonegociação só atualiza cotação. Eventos conhecidos sem negócio avançam progresso; encerramento marca referência defasada.

V2 aditiva cria `bo_log`, `bo_checkpoint`, `bo_transporte`, `bo_evento`, `bo_negocio`, `bo_cotacao` e `bo_posicao`. As vinte posições possíveis têm PK conta/ativo e leitura com igualdade parametrizada, snapshot SQL único e limite cinco. Essa cardinalidade pequena pode justificar Seq Scan escolhido pelo PostgreSQL, sem forçar índice ou carregar histórico para a consulta. Fatos mínimos de deduplicação RF07 não equivalem ao histórico/razão contábil RF08.

Cálculo usa racionais reduzidos: 131072 bits/componente, temporários262146, persistência textual canônica com limites e validação. Excesso reverte todos os efeitos e bloqueia a partição, sem aproximação silenciosa. Sondas e limites de custo no ADR-015; nenhum RNF04/capacidade é presumido.

## Razão D0 e consultas — RF08b

Implementa a fatia RF08b do [ADR-016](../adrs/ADR-016-razao-liquidacao-d2.md), sem liquidação D+2, data de movimento ou outbox (RF08d). Na mesma transação e checkpoint da RF07, `AplicarPosicoesActor` grava o fato econômico do negócio (`bo_negocio_fato`: ativo, `dataSessao`, data de liquidação, preço `NUMERIC(9,2)`, quantidade inteira, valor `NUMERIC(15,2)` = preço × quantidade, contas e ordens), uma liquidação `PENDENTE` por (negócio, lado) com unicidade e os lançamentos D0 do plano de contas (`CAIXA`, `CAIXA_A_RECEBER`, `CUSTODIA`, `CUSTODIA_A_RECEBER`, contraparte `CAMARA`), por unidade BRL/ativo. Autonegociação gera os dois lados. O domínio recusa transação desbalanceada e o adaptador confere no banco, antes do commit, contagens e Σdébitos = Σcréditos por transação e unidade; divergência é `RAZAO_DESBALANCEADA`.

Calendário puro (`Calendario`): seg–sex menos os feriados nacionais de data fixa em lei de 2026–2027 (`bo_feriado`, cobertura em `bo_calendario`, sem API de edição). D+2 conta dois dias úteis a partir da `dataSessao` do envelope, nunca de `ocorridoEm`; sessão em dia não útil parte do próximo dia útil. Qualquer dia fora da cobertura é `CALENDARIO_INSUFICIENTE`. Carnaval, Sexta-feira Santa e Corpus Christi (datas móveis, não feriados nacionais fixos) não entram.

V3 aditiva cria as tabelas acima; `bo_lancamento` e `bo_negocio_fato` recusam UPDATE/DELETE por gatilho (`APPEND_ONLY`); correção futura só por estorno vinculado. No bootstrap, negócio RF07 sem fato (`SEM_ORIGEM`, anti-join executado uma vez) é quarentena `RAZAO_SEM_ORIGEM`: prontidão DOWN e consultas 503, sem backfill nem reset. Recuperação decidida pelo usuário: parar o BO, recriar o volume `bo-dados`, remover o grupo Kafka `back-office-posicoes` (o offset confirmado à frente do banco vazio seria `HISTORICO_INCOMPATIVEL`) e reiniciar para reconstruir desde a origem do log. Calendário insuficiente, desbalanço e conflito de identidade seguem a quarentena RF07, sem nova tentativa automática.

`GET /api/v1/negocios`, `GET /api/v1/negocios/{id}` e `GET /api/v1/razao` exigem JWT `prospero-back-office` com escopo próprio `razao:leitura`; a conta vem só do subject. Listas mais recentes primeiro, padrão 50 e máximo 100, cursor opaco base64url com tipo, sequência e vínculo resumido de tipo/conta: cursor inválido, adulterado, de outra conta ou de outra listagem é o mesmo 400 `CONSULTA_INVALIDA`. Negócio inexistente, malformado ou sem participação é o mesmo 404 `NEGOCIO_NAO_ENCONTRADO`. Respostas mostram só o lado da própria conta (ordem própria, sem contraparte) e partidas contra `CAMARA`; `no-store`; bootstrap/atraso/quarentena ou falha de leitura são 503 `RAZAO_INDISPONIVEL`. Leituras usam índices `(conta, id)` com igualdade parametrizada e `LIMIT`. O proxy (Nginx e dev) injeta `BO_TOKEN_RAZAO` (CONTA-001) só nas rotas exatas GET/HEAD; a sequência interna decodificável no cursor revela apenas volume relativo, como o id de negócio já revela. Logs/métricas continuam só com motivo enumerado, partição e offset.

Evidência: domínio (calendário com datas fixas, propriedade de balanço/conservação 100 sementes × 100 negócios, cursor), REST Assured das consultas, IT PostgreSQL `RazaoPostgresIT` (atomicidade com falha injetada, reentrega, identidade divergente, append-only, unicidade, invariante, calendário, V2 → V3 com dados) e `razao.xlsx` por APIs reais duas vezes na CI.

## Implantação, prontidão e recuperação

BO depende de PostgreSQL próprio e `kafka-init` concluído. Porta8084 loopback, banco `prospero_bo`, imagem/volume/rede próprios. `/api/v1/sistema`, `/q/openapi`, Swagger e métricas permanecem técnicos; `/q/health/ready` inclui sincronização da projeção. `prospero.posicoes.em_dia`, `.falhas` e `.quarentena` têm labels finitos, sem payload/conta em métricas. Processo vivo ou banco acessível não comprovam posição utilizável.

Identidade do tópico, origem zero, fim, offset confirmado Kafka e checkpoint PostgreSQL são confrontados. Lock transacional por partição impede efeitos fora de ordem entre gerações. Falha após poll rebobina; Kafka só recebe ack depois do commit e da confirmação do checkpoint. Tópico recriado, lacuna, bootstrap parcial ou progresso incompatível bloqueiam. Retenção integral e uma sessão/log são limites atuais; não implementar reset automático para contorná-los.

Migração aplicada não é editada. Retornar à imagem anterior preserva tabelas mas interrompe RF07; preferir correção para frente. Reconstrução deve parar somente o consumidor BO e tratar suas tabelas/grupo como uma unidade com cópia recuperável; nunca limpar parcialmente nem apagar volumes por rotina; a recriação única de `bo-dados` para o legado RF07 é decisão do usuário no [ADR-016](../adrs/ADR-016-razao-liquidacao-d2.md). Hash não autentica produtor: Kafka PLAINTEXT permite forjar negócio coerente e alterar PnL, risco local herdado dos ADRs013/014.

Verificação local: domínio/propriedades, REST/JWT, consumidor controlado e PostgreSQL real. CI inclui ITs BO em bancos efêmeros próprios, workbook `posicoes.xlsx` duas vezes e E2E desktop/mobile com PNGs. A planilha exige perfil isolado antes de outras negociações DELTA3; fecha posições/PnL e restaura40 por negócio real, sem reset de dados. Resultados finais no PR/roadmap, sem alegar execução HTTP antes do gate.

## Liquidação, recuperação e pendências

**Decisão RF08:** o [ADR-016](../adrs/ADR-016-razao-liquidacao-d2.md) fixa calendário, data de movimento, plano de contas, estados `PENDENTE → LIQUIDADO`, contrato `liquidacao.eventos.v1` e expectativa de crédito no MO. Calendário e data de liquidação existem só no BO; a liquidação local é uma transação BO com UPDATE condicional; claim/lease/`DESCONHECIDO` abaixo permanecem como avaliação para efeitos externos futuros, não adotados. Os parágrafos seguintes registram a análise anterior.

D+2 precisa de calendário/dias úteis/feriados, estados, falhas, conciliação e relógio controlável por API de teste. Diferenciar disponível, reservado e liquidado e publicar efeitos idempotentes para MO. Repetir job/evento após reinício não pode liquidar novamente; resultado desconhecido exige reconciliação segura, sem assumir falha e refazer efeitos.

Resolver conflito conceitual entre append-only e `lancamento.estado`; estados de liquidação não estão completamente modelados. Definir projeção não realizada por cotação/idade, passagem de ordenação por ativo para conta, participantes, IDs, e fechamento de caixa/custódia. O dicionário não define toda a contabilidade nem um scheduler adotado.

Testes futuros: long/short, aumento/redução/zeragem/virada, idempotência, transação negócio/razão/posição, conservação de caixa/custódia, D+2 sem aguardar dias reais, falha/reinício e conciliação. [Roadmap BO](../../back-office/ROADMAP.md); RNF01/RNF05/RNF06 e observabilidade/operação RNF07/RNF08.

## Jobs recuperáveis de liquidação — avaliação para RF08

**Parecer: válido com ressalvas, desenho futuro.** Modelar a unidade de liquidação como job persistente identificado, com estados candidatos `PENDENTE`, `PROCESSANDO`, `CONCLUIDO` e `DESCONHECIDO`. O estado operacional do job é separado dos negócios/lançamentos append-only. Não há scheduler, tabela ou worker implementado. Calendário D+2, unidade do job e transições autorizadas precisam ser definidos antes da migração.

| Situação | Tratamento proposto |
| --- | --- |
| Pendente e elegível | Adquirir por mudança condicional atômica; conferir que exatamente um worker venceu e confirmar o claim antes de iniciar o efeito |
| Processando com proprietário ativo | Não tomar o trabalho de outra execução; propriedade/geração e validade são verificadas conforme contrato |
| Processando abandonado após queda/perda de autorização | Encaminhar para revisão/reconciliação como desconhecido; nunca retornar a pendente ou liquidar novamente automaticamente |
| Efeito comprovadamente concluído | Reconhecer conclusão pela evidência durável, sem executar de novo |
| Resultado ainda incerto | Manter desconhecido com motivo/auditoria; nova tentativa exige decisão explícita e exclusão do worker anterior |
| Concluído | Reentrega/varredura não repete efeito |

Claim impede disputa inicial, mas não prova execução. Uma lease expirada não prova que o worker morreu nem que nada aconteceu. Se adotada geração de propriedade, o destino do efeito precisa verificá-la: conferência transacional local pode impedir commit do worker antigo, enquanto token guardado apenas no BO não impede sozinho um envio externo. Transações de aquisição são curtas. `FOR UPDATE SKIP LOCKED` é alternativa para disputar lotes de fila, não mecanismo suficiente de posse após commit ou prova de integridade do conjunto. [SELECT/filas PostgreSQL 16](https://www.postgresql.org/docs/16/sql-select.html#SQL-FOR-UPDATE-SHARE).

Na liquidação simulada local, marcador único da operação, lançamentos, projeções pertinentes, conclusão e outbox devem confirmar/reverter na mesma transação BO. Resposta de commit perdida exige consultar evidência persistida; não repetir com nova identidade. Essa atomicidade não abrange Kafka, banco alheio ou envio externo. O relay da outbox continua com reentrega/deduplicação do ADR-002; não confundir sua recuperação com autorização para refazer um efeito de job desconhecido. [Transações PostgreSQL](https://www.postgresql.org/docs/16/tutorial-transactions.html), [outbox e janela de duplicação](https://microservices.io/patterns/data/transactional-outbox.html).

**[DOC-GAP] Notas de corretagem:** não constam dos requisitos atuais. A menção do usuário é proposta de geração/disponibilização a detalhar; não adota taxas, tributação ou envio externo. Definir conteúdo, identidade/versão, privacidade, correção e canal. Um envio hipotético tem janela “destinatário recebeu, BO caiu antes de registrar”: claim/outbox não eliminam a incerteza. Sem recibo/consulta confiável ou contrato de idempotência do destinatário, manter revisão e não reenviar automaticamente. Não contratar provedor nem enviar mensagens reais nesta avaliação.

Critérios futuros: dois workers disputando; queda antes/depois do claim, durante efeito e após commit antes da resposta; worker antigo retomando; revisão sem duplicar caixa/custódia/lançamento; reentrega; relógio/calendário controlados por API; autorização/auditoria da reconciliação. Sem execução desses cenários nesta entrega. RF08 fechará identidade/unidade, abandono, propriedade, evidências, transições e limites de lote.

## Histórico frio e fechamento por conjuntos — avaliação para RF08

**Parecer: válido com correções.** Preservar negócios/razão originais e histórico de ordens encerradas; retirar da área quente somente depois de arquivo verificado/restaurável e política explícita. FO mantém suas ordens/SAGA e BO seus negócios/razão; leitura histórica não autoriza acessar outro banco. Não há volume financeiro, partições, arquivo frio ou consulta histórica implementados para dimensionar uma solução.

Partições por tempo são opção após medição. `DETACH PARTITION` conserva uma tabela independente, mas não cria backup, não libera por si espaço total e a consulta ao pai deixa de incluí-la. PK/UNIQUE particionada exige compatibilidade com chave de partição: preservar unicidade/deduplicação global antes de mudar o modelo. Arquivo local privado em volume persistente é candidato sem custo externo; outro container no mesmo disco não cria proteção contra perda desse disco. [Particionamento PostgreSQL 16](https://www.postgresql.org/docs/16/ddl-partitioning.html).

Antes de retirar cópia quente: verificar jobs/SAGAs/reservas pendentes, horizonte de reentrega/replay/deduplicação, dependências locais e saldo inicial necessário à reconstrução. Exigir manifesto de período/esquema/versão/contagem/hash, validação de restauração, checkpoints e recuperação da falha entre exportar, verificar e retirar. Checksum detecta alteração, mas sua origem e armazenamento também precisam de proteção. `COPY` é candidato de exportação, não pacote completo de DDL/restauração; caminhos/comandos nunca vêm livres da API. Sem prazo legal inventado para este simulador. [COPY](https://www.postgresql.org/docs/16/sql-copy.html).

Consulta sob demanda precisa de autorização por conta/período, paginação/ordenação/volume limitados e estado explícito de recuperação/indisponibilidade; nunca retornar vazio para esconder dado arquivado. Restauração temporária e leitor de arquivo são alternativas pendentes, não duas implementações exigidas. Ameaça, retenção, criptografia e gestão de chaves serão definidas antes de escolher mídia/formato. Arquivamento não substitui backup/restauração.

Relatórios usam `SELECT`/agregações ou `INSERT … SELECT`/upsert em sumários próprios. **Não usar UPDATE para reescrever negócio/lançamento original.** `UPDATE` por conjuntos cabe em projeções/estado operacional mutáveis aprovados; correção contábil permanece novo registro vinculado à origem. Definir semântica do conjunto vazio por métrica; em `UPDATE … FROM`, cada alvo deve corresponder a uma única linha agregada. [Agregações](https://www.postgresql.org/docs/16/functions-aggregate.html), [UPDATE](https://www.postgresql.org/docs/16/sql-update.html).

Lotes: período/cursor estável, SQL parametrizado, limites de tempo/linhas, checkpoint e retomada idempotente. Recalcular/substituir resumo ou controlar contribuição única, sem somar novamente após crash. Fixar corte, calendário/fuso e correlação UTC, eventos tardios e versão de fechamento. `MAX(id)` não comprova ordem de commit. Coordenação limitada entre lotes pode permanecer na aplicação; evitar carregar tudo e atualizar linha a linha. Isso não proíbe loops legítimos do matching/domínio.

Medir planos, WAL, locks, versões antigas/autovacuum e efeito sobre tráfego. `EXPLAIN ANALYZE` executa a consulta, inclusive escrita: usá-lo somente em laboratório controlado. [EXPLAIN](https://www.postgresql.org/docs/16/using-explain.html), [MVCC/manutenção](https://www.postgresql.org/docs/16/routine-vacuuming.html). Testes futuros: conciliação antes/depois do arquivo, restauração/versionamento, corrupção/ausência, acesso entre contas, corte/eventos tardios, falhas em cada etapa, deduplicação após mover dados e retomada do fechamento sem duplicar totais. Nada disso foi executado nesta avaliação.

## Modelo de dados de referência

Dicionário migrado da proposta histórica. **Planejado e incompleto**, não schema aprovado nem tabelas existentes. Tipos bigint não definem escala financeira; ausência de marcação não decide nulabilidade/índice/constraint. Referências a IDs externos não criam FK entre bancos. `ativo` representa referência local a distribuir por contrato, sem tabela compartilhada. Refinar antes de migrações.

### `ativo` — referência preliminar
| Coluna | Tipo | Chave |
| --- | --- | --- |
| `ticker` | `varchar(12)` | PK |
| `tick` | `bigint` | — |
| `lote_minimo` | `bigint` | — |
| `tunel_pct` | `int` | — |
| `negociavel` | `boolean` | — |

### `negocio` — referência preliminar
| Coluna | Tipo | Chave |
| --- | --- | --- |
| `id` | `varchar(32)` | PK determinística |
| `ativo` | `varchar(12)` | — |
| `preco` | `bigint` | — |
| `quantidade` | `bigint` | — |
| `ordem_compra` | `uuid` | — |
| `ordem_venda` | `uuid` | — |
| `agressor` | `char(1)` | — |
| `data_liquidacao` | `date` | — |

### `lancamento` — referência preliminar
| Coluna | Tipo | Chave |
| --- | --- | --- |
| `id` | `bigserial` | PK |
| `negocio_id` | `varchar(32)` | FK local para `negocio` |
| `conta_contabil` | `varchar(32)` | — |
| `debito` | `bigint` | — |
| `credito` | `bigint` | — |
| `estado` | `varchar(12)` | — |

### `posicao` — referência preliminar
| Coluna | Tipo | Chave |
| --- | --- | --- |
| `conta_id` | `varchar(32)` | PK composta |
| `ativo` | `varchar(12)` | PK composta |
| `quantidade` | `bigint` | — |
| `preco_medio` | `bigint` | — |
| `pl_realizado` | `bigint` | — |

## Segurança, observabilidade e testes

Seguir ADR-003 para DTOs, versão, Swagger, status e erros; contratos de negócio ainda precisam de definição. Autenticação e autorização por conta/papel são pendências, sem RBAC presumido. Não registrar credenciais ou payloads sensíveis. Entradas exigem limites e invariantes; o endpoint técnico não comprova proteção do fluxo financeiro.

Fundação: saúde de banco/Kafka, métricas técnicas, logs com correlação e exportação OTLP. Não afirmar trace financeiro completo, métricas de domínio ou capacidade comprovada. Testes atuais usam JUnit/REST Assured/ArchUnit; perfil de contrato desativa banco/Kafka, smoke Compose exercita dependências reais. Casos futuros via API/planilha conforme ADR-004 complementam invariantes internas e testes de concorrência/recuperação.

## Decisões e rastreabilidade

[ADR-001](../adrs/ADR-001-arquitetura-backend.md) define fronteiras/backend; [ADR-002](../adrs/ADR-002-padroes-dominio.md), padrões; [ADR-003](../adrs/ADR-003-contratos-api.md), contratos; [ADR-004](../adrs/ADR-004-testes-planilha.md), testes; [ADR-005](../adrs/ADR-005-versoes-fundacao.md), stack. [PRD-001](../prds/PRD-001-simulador-prospero.md) é a fonte dos requisitos. Este HLD detalha o [ecossistema](HLD-001-arquitetura-prospero.md), sem alterar decisões.

## RF08d — data de movimento e liquidação

Integrada pelo PR #47, conforme ADR-016. `POST /api/v1/data-movimento` recebe `{"dataAlvo":"2026-10-27"}`. A flag `BO_RELOGIO_HABILITADO` permanece desligada por padrão (404 antes da autenticação); ligada, exige audiência BO, grupo `liquidacao:administrar`, `sub` e `jti` limitados. `python3 scripts/mercado.py token --escopo liquidacao` emite o token local, sem passá-lo ao navegador. Os proxies descartam a autorização recebida na rota administrativa.

A data inicia no primeiro uso com a data civil de São Paulo, persiste em `bo_data_movimento` e só avança: igual retorna 200 sem efeito; retroativa 409; mais de dez dias úteis ou fora da cobertura do calendário retorna 422. Lock da linha mais UPDATE condicional serializam avanços concorrentes. A sessão do matching e o relógio de cotação permanecem independentes.

Cada avanço confirma a data, um lote de até 50 lados e uma auditoria append-only na mesma transação. `liquidacoes` na resposta e em `bo_movimento_auditoria` conta somente esse lote síncrono. O worker completa os demais lotes e negócios que chegarem depois, pela data persistida; cada lote deixa sua evidência nos lançamentos D2, estado e outbox. Avanço repetido não duplica efeitos. O worker não participa da prontidão; bootstrap/defasagem da projeção impede iniciar efeitos.

Cada lado elegível muda condicionalmente de PENDENTE para LIQUIDADO e grava duas partidas D2 balanceadas e uma mensagem em `bo_outbox`, numa transação BO. A conferência SQL usa negócio/lado/momento pelo índice único existente. Queda antes do commit reverte tudo; depois do commit, o relay retoma a mensagem persistida. O relay espelha o padrão MO, com produtor idempotente, acks=all, lote limitado, publicação com confirmação e marca posterior; pode reenviar o mesmo payload após queda. A deduplicação no MO preserva crédito único. Mensagens publicadas são removidas em lotes após um dia; estado e identidade financeira permanecem.

Invariante ou constraint violada reverte o lote e grava quarentena em nova transação, inclusive no avanço síncrono. Consultas de negócios/razão e novos avanços respondem 503. O worker retenta falhas transitórias; se falhar a gravação da quarentena, preserva em memória a necessidade do marcador e retenta apenas a persistência, sem refazer efeitos. Motivos são enumerados e logs da aplicação não incluem conta, preço ou payload. No perfil de produção, o logger `org.hibernate.orm.jdbc.error` fica desligado porque o detalhe de uma constraint do driver pode incluir a linha financeira; o teste usa dados sintéticos e preserva esse diagnóstico.

A V4 é aditiva e não modifica as migrações aplicadas. `bo_negocio_origem` guarda o id do evento NEGOCIO na transação D0 para a causação do crédito. Fatos gravados pela RF08b antes dessa tabela não contêm essa origem: não há backfill inferido. Se houver pendência anterior, o lote falha fechado com `LIQUIDACAO_SEM_ORIGEM`; exige reconstrução explícita do banco BO desde a origem do log, pelo procedimento de legado acima, sem reset automático. A V7 do MO também não cria expectativas para negócios antigos: antes de usar um histórico anterior à RF08c, recuperar explicitamente as expectativas pela origem; esta entrega não altera nem apaga volumes existentes.

Recuperação de migração: preservar backup privado antes de atualizar um banco com dados; correções de schema usam nova versão Flyway. Restaurar backup completo e imagem compatível é alternativa operacional, sem Flyway Undo. Não foi executada restauração nesta entrega. A RF08e acrescenta conciliação BO × MO, planilha de liquidação, prova de falhas dos processos completos e recusa da preparação ativa por padrão; estado e evidências no roadmap.

## RF08e — histórico e conciliação por movimentos

Angular consulta negócios e razão com páginas de 20, até 100 itens por seção, atualização manual e timeout de oito segundos; em falha preserva o snapshot com aviso. Valores textuais são formatados sem conversão financeira a ponto flutuante. O proxy injeta apenas o token de leitura da conta demo; data de movimento administrativa fica fora da tela.

`scripts/liquidacao.py` usa somente APIs para os fatos/saldos: espera a expectativa MO de cada lado pendente, captura carteiras já com os débitos D0, avança a data no BO e confirma crédito aplicado no MO. Confere duas partidas D2 por lado, unidade/valor/data/transação e variação exata dos recursos, inclusive CONTA-004. As reservas não podem mudar durante a janela; saldos absolutos preparados antes não são tratados como razão histórica.

Na CI descartável, worker/relay BO ficam inicialmente suspensos pela propriedade existente `prospero.liquidacao.worker-habilitado`; o POST confirma somente seu lote de até 50 lados. O harness confere configuração e labels, interrompe MO, observa BO parcialmente liquidado, interrompe BO e o recria com worker/relay ativos, preservando banco e log. BO conclui enquanto MO está parado; MO volta e confirma créditos. `finally` restaura os serviços da própria execução. Não é prova de backup, disco perdido ou host reiniciado. Duas execuções de `liquidacao.xlsx`, segunda conciliação sem novos créditos e capturas E2E de D0/D+2 complementam a recuperação; resultados reais ficam no PR.
