# HLD-005 — Back office

Data: 2026-10-17. Desenho vigente e planejado do contexto, sem nova decisão de produto.

## Responsabilidade e estado

Contexto de pós-negociação: RF07/RF08 e eventos de trajetória RF09. Fundação técnica existe; não há registro de negócio, cálculo de posição, razão ou liquidação implementados. PnL e posição pertencem ao BO, não ao núcleo de risco MO.

Futuro: consumir execuções, registrar negócio/razão/posição atomicamente, reconstruir projeções, atualizar resultado por cotação e simular caixa/custódia D+2. Não acessa tabelas FO/MO/ME; participantes e dados necessários devem chegar em contratos.

## Stack e organização

Java 26, Quarkus 3.40.1, Maven Wrapper, REST Jackson/Bean Validation, JPA/Hibernate, PostgreSQL 16 e Flyway; cliente Kafka, SmallRye OpenAPI, Health, Micrometer e OpenTelemetry. Versões resolvidas/imagens e overrides: [libs](../libs.md) e ADR-005. Build e imagem próprios, sem artefato compartilhado entre serviços. Não há dependência de implementação de outro contexto.

Organização por funcionalidade sob `br.prospero.bo`: REST → Actor; Actor coordena domínio/Helpers puros e repositórios JPA separados. Adaptadores Kafka futuros chamam casos de uso sem passar pelo REST. Entidades JPA não são DTOs nem domínio. Pacotes vazios não são componentes de negócio implementados.

## Interfaces e implantação atuais

`GET /api/v1/sistema` retorna identidade técnica. Porta externa `8084` em loopback → `8080` no container; `/q/health/ready`, `/q/openapi`, `/q/swagger-ui/` e `/q/metrics` são técnicos. Configuração usa variáveis locais fora do Git. Banco/container exclusivo `postgres-back-office`, banco `prospero_bo`, rede de dados própria. Não acessar tabelas de outro serviço.

`src/main/resources/db/migration/V1__identificar_schema.sql` só identifica o schema. Flyway mantém histórico, sem tabelas financeiras. Build com JDK26: `./mvnw verify` no diretório do serviço; operação pela skill subir-sistema. Processo pode iniciar sem outro MS Java, mas depende de sua infraestrutura para ficar pronto; não confundir autonomia com conclusão de negócio sem participantes.

## Processamento e invariantes futuros

ID determinístico de negócio permite deduplicação atômica com inserção, lançamentos e atualização de posição. Uma inserção ignorada por conflito não pode repetir efeitos. O contrato atual `NEGOCIO` de `matching.eventos.v1` já identifica preço, quantidade, ativo, sessão, negócio e ambas as contas/ordens/intenções (ADR-013); a lacuna histórica de identificação dos participantes está resolvida. A ordenação, checkpoint e aplicação no BO ainda precisam de implementação.

Negócios e razão têm histórico append-only; correções exigem estratégia explícita, sem alterar silenciosamente negócio executado. Posição é projeção reconstruível: quantidade com sinal, aumento altera preço médio, redução realiza resultado e virada abre lado novo. Arredondamento e limites ainda precisam de decisão; o cálculo do Minerva não cobre short/virada nem o contrato de resultado realizado; a [proveniência e restrição de cópia](HLD-003-middle-office.md#proveniência-e-limites-de-reuso) continuam aplicáveis. Fluxo acumulado de compras/vendas não equivale a PnL realizado. Calendário só de dias da semana não resolve feriados/D+2; não importar sua regra como decisão local.

A proposta estrutural da RF07 está no [ADR-015](../adrs/ADR-015-projecao-posicoes-bo.md); regras financeiras e significado da posição inicial aguardam decisões no [roadmap](../roadmap.md#rf07--posições-e-resultado-financeiro). Persistência mínima de fatos para deduplicação da RF07 não representa razão ou liquidação RF08 implementadas.

## Liquidação, recuperação e pendências

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
