# HLD-004 — Matching engine

Data: 2026-10-17. Desenho vigente e planejado do contexto, sem nova decisão de produto.

## Responsabilidade e estado

Contexto de execução: RF05, publicação de mercado RF06 e cancelamento RF10. RF05a ([ADR-013](../adrs/ADR-013-matching-execucao.md)) implementou o núcleo puro `livro/dominio/MotorMatching`: livro por ativo, FIFO, Limit/Market, DIA/IOC/FOK, preço passivo, faixa/reserva e IDs determinísticos. A RF05b acrescenta adaptador Kafka, posse e replay; stops/OCO/sessão seguem na RF05c. Estado das evidências no roadmap; metas RNF04 continuam sem medição financeira final.

Desenho de integração: um escritor por ativo, sequenciado pela partição Kafka; no núcleo atual, um escritor por instância de `MotorMatching`, mesmo quando contém vários ativos. O núcleo puro recebe comando e produz eventos. Não consulta JPA/banco por ordem nem decide poder de compra. Stops ficam no ME como simplificação já descrita no domínio; risco e reservas precedem admissão ao matching.

## Stack e organização

Java 26, Quarkus 3.40.1, Maven Wrapper, REST Jackson/Bean Validation, JPA/Hibernate, PostgreSQL 16 e Flyway; cliente Kafka, SmallRye OpenAPI, Health, Micrometer e OpenTelemetry. Versões resolvidas/imagens e overrides: [libs](../libs.md) e ADR-005. Build e imagem próprios, sem artefato compartilhado entre serviços. Não há dependência de implementação de outro contexto.

Organização por funcionalidade sob `br.prospero.me`: REST → Actor; Actor coordena domínio/Helpers puros e repositórios JPA separados. Adaptadores Kafka futuros chamam casos de uso sem passar pelo REST. Entidades JPA não são DTOs nem domínio. Pacotes vazios não são componentes de negócio implementados.

## Interfaces e implantação atuais

`GET /api/v1/sistema` retorna identidade técnica. Porta externa `8083` em loopback → `8080` no container; `/q/health/ready`, `/q/openapi`, `/q/swagger-ui/` e `/q/metrics` são técnicos. Configuração usa variáveis locais fora do Git. Banco/container exclusivo `postgres-matching-engine`, banco `prospero_me`, rede de dados própria. Não acessar tabelas de outro serviço.

`V1__identificar_schema.sql` identifica o schema. `V2__checkpoint_matching.sql` guarda identidade dos tópicos, sessão e último comando integralmente publicado por partição, sem persistir livro por ordem. Build com JDK26: `./mvnw verify` no diretório do serviço; operação pela skill subir-sistema. Prontidão inclui posse/replay; processo vivo não equivale a fluxo financeiro disponível.

## Livro e processamento planejados

Na RF05a, `Livro` usa `TreeMap` por preço, `ArrayDeque` FIFO por nível, índice por ID e contagem de ativas por conta. `MotorMatching` recebe `Admissao` e devolve records de `Evento`; stops por gatilho e o processador de partição permanecem planejados. Não prometer cancelamento O(1) sem definir fila/índice; medir antes de otimizar.

Comando autorizado → prioridade preço-tempo → negócios a preço passivo, parciais e eventos de estado → publicação recuperável. Definir Market/Limit/Stop/Stop Limit, Dia/IOC/FOK, verificação integral FOK, falta de liquidez, cascatas e OCO após parciais. Inserção e cancelamento sem negócio também alteram bid/ask/profundidade.

## Persistência, recuperação e pendências

O núcleo atual mantém o livro apenas em memória. Na RF05b, o log Kafka permitirá reconstrução e o PostgreSQL próprio guardará o último offset publicado, conforme ADR-013. Snapshots ficam adiados até haver necessidade medida. Não compartilhar banco/entidade e não criar tabela do livro no caminho crítico.

Na RF05b, comprovar retenção suficiente, replay e rebalanceamento com posse exclusiva dos livros, sem transações Kafka. Publicação interrompida exige reconstrução e deduplicação com IDs determinísticos antes de declarar recuperação segura. Relógio, desempate e IDs não podem mudar negócios no replay.

Na RF05a, reentregas de IDs já decididos não geram eventos, inclusive quando o ID reaparece em outro ativo. FOK verifica a execução integral dentro do teto de 256 negócios antes de tocar o livro. A Market distingue corte pelo túnel de corte pelo preço reservado. A RF05c.1a acrescenta encerramento explícito ao núcleo puro: expiração de ofertas DIA, recusa de admissão na sessão encerrada e bloqueio da troca de data enquanto a anterior estiver aberta. Após o fechamento, uma nova sessão do núcleo reinicia base e sequências. O adaptador mantém uma sessão por log: este comportamento interno não implementa virada diária operacional. Deduplicação histórica permanece preservada; descarte depende de contrato de retenção/replay próprio.

Cada instância de `MotorMatching` exige um único escritor, inclusive se contiver mais de um ativo; seus mapas e a deduplicação não suportam chamadas paralelas. A integração deve preservar essa posse. O conjunto de IDs cresce com os comandos da sessão, mesmo rejeitados: orçamento e retenção coerentes com replay precisam ser resolvidos antes da operação contínua. Preços e parâmetros têm guarda de representação (até 15 dígitos, escala entre −6 e 8) antes de aritmética; preços continuam positivos, até 1.000.000,00, com até duas casas significativas e múltiplos do tick. A guarda recusa extremos sem arredondar valores.

Catálogo de comandos/eventos, chaves e passagem para ordenação por conta precisam de desenho conjunto. Testes futuros: mesma sequência/mesmos negócios, conservação, preço passivo, livro não cruzado ao final do processamento, stops/OCO, corrida cancelamento/execução e falhas em publicação/rebalanceamento. A referência histórica de ordem `0,10` atravessando três níveis não fornecia preços/quantidades suficientes. O [exemplo didático do README](../../README.md#impacto-de-mercado-uma-ordem-pequena-atravessa-o-livro) agora explicita um conjunto hipotético; não é teste de aceite executado nem definição de lote/tick/escala financeira.

Ativo quente limita throughput de sua sequência; partições/GC/alocação exigem medição. JFR/JMH/async-profiler, ZGC e bibliotecas de propriedades são alternativas não adotadas por este HLD. [Roadmap ME](../../matching-engine/ROADMAP.md); RNF01–RNF04/RNF06–RNF08.

## Medir antes de otimizar — laboratório proposto

**Parecer: válido com ressalvas.** JMH e async-profiler complementam o desenho P06 e ADR-007; não estão instalados/adotados. O núcleo RF05a existe, mas a integração financeira ainda depende da RF05b. Alocação/serialização, árvore, locks, Kafka e quota são hipóteses; nenhum gargalo local foi identificado. Não complexificar estrutura por intuição nem transformar benchmark de terceiros em capacidade do Próspero.

1. Fixar correção: log/seed, profundidade, níveis, concentração por ativo e proporções de comandos; candidatos produzem mesmos negócios/eventos/estado/replay, preservando preço-tempo e quantidades.
2. JMH mede núcleo puro em harness/JVMs de laboratório, separado do runtime do serviço. Entradas em estado parametrizado, retorno/Blackhole contra eliminação de código e constantes; aquecimento, medição, forks independentes e repetições. Restaurar livro sem retirar do tempo medido trabalho real ou medir estado progressivamente esvaziado. Um escritor por livro; benchmark separado para serialização quando pertinente. Definir duração/forks após piloto com orçamento; não importar os 60 s candidatos da P06 automaticamente.
3. Sob carga integrada P06, medir oferta/admissão/conclusão, filas/lag, falhas/descartes e latências. Separar núcleo, espera na partição, publicação e espera por contraparte. Média ns/op JMH não comprova p99 boleta→negócio; não inventar percentil adicional para a meta matching <1 ms/ordem do PRD.
4. async-profiler: CPU, wall, alloc e lock são perfis diferentes. Correlacionar com GC/safepoints, bytes/operação, heap/dados vivos/RSS e throttling. Alocação não equivale a retenção; comparar janelas com/sem profiler e registrar unidade, resolução, perdas e interferência.
5. Otimizar uma dimensão sustentada pelo perfil, repetir comparação e validar invariantes/replay. Nenhum ganho ou pausa é garantido pela ferramenta.

Fontes JMH: [projeto oficial](https://github.com/openjdk/jmh/blob/master/README.md), [DCE](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_08_DeadCode.java), [constantes](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_10_ConstantFold.java) e [forks](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_12_Forking.java). Perfis: [modos async-profiler](https://github.com/async-profiler/async-profiler/blob/master/docs/ProfilingModes.md).

Laboratório deve fixar versões/artefatos compatíveis com Temurin26/kernel/arquitetura e conferir ferramentas disponíveis na imagem JRE. Runtime atual: 384 MiB/1 CPU, JVM percebe dois processadores, UID10001, filesystem somente leitura e tmpfs limitado; orçar processo JMH/forks, memória nativa, gerador/telemetria e saídas. Não executar carga de outros projetos para obter números. Perfis privados/limitados com dados sintéticos, sem publicar payloads ou segredos.

Attach requer identidade/permissões adequadas; `perf_events` pode estar bloqueado. Avaliar agente de startup ou motor `ctimer` suportado, declarando perda de resolução/stacks do kernel. Não habilitar container privilegiado, socket Docker, sysctls globais ou desativar seccomp por rotina. Se o perfil não for viável no orçamento, registrar a lacuna. [Containers](https://github.com/async-profiler/async-profiler/blob/master/docs/ProfilingInContainer.md), [motores CPU](https://github.com/async-profiler/async-profiler/blob/master/docs/CpuSamplingEngines.md), [compatibilidade](https://github.com/async-profiler/async-profiler/blob/master/CHANGELOG.md). Nenhuma instalação, carga ou benchmark nesta avaliação.

## Contrato de integração RF05b

Desenho fechado no refinamento complementar de 2026-10-18; estado de implementação e evidências no roadmap. `risco.comandos.v1` aceita envelopes de versão1 (legados) e versão2 (FO passa a emitir), esta com `dados.dataSessao` e `ordens[].validade`. A versão1 continua avaliável pelo MO, mas não admite ordens no ME sem os metadados ausentes. Stop/OCO também aguardam RF05c; não admitir apenas uma perna. Reserva nova simples e outbox da admissão confirmam na mesma transação.

`ORDEM_ADMITIDA` usa chave ticker e envelope `id,tipo,versao,origem,correlacao,ocorridoEm,conta,traceparent,dados`; origem `middle-office`, versão1, correlação=intenção, ID UUID determinístico derivado de `admissao:<intenção>`. Dados: `ordem,ticker,lado,tipo,validade,quantidade,precoLimite,precoMaximo,dataSessao`. Preço máximo da compra deriva exatamente do valor reservado dividido pela quantidade. Valores decimais são texto, duas casas, sem arredondamento silencioso; envelope limitado a16KiB e campos/tipos exatos, incluindo UUID canônico, conta/ativo permitidos e chave igual ao ticker.

Eventos individuais em `matching.eventos.v1`: raiz `id,tipo,versao,origem,correlacao,ocorridoEm,ticker,dataSessao,traceparent,dados`, origem `matching-engine`, versão1. Correlação e horário vêm da admissão agressora, mesmo no replay. ID = UUID determinístico de `matching:<id-admissão>:<índice-zero-do-evento>`. Eventos de ordem identificam `ordem,intencao,conta` da ordem alvo; aceite acrescenta sequência, oferta acrescenta lado/preço/restante, rejeição acrescenta motivo e encerramento acrescenta motivo/executada/cancelada. Negócio identifica `negocio,preco,quantidade,ordemCompra,intencaoCompra,contaCompra,ordemVenda,intencaoVenda,contaVenda,agressor`. Identidade do negócio e IDs de eventos são preservados após publicação parcial.

Laboratório limitado a uma sessão por log: primeira admissão define a data; data divergente interrompe antes da mutação. Tópicos de três partições fixas e retenção integral (tempo/bytes sem expurgo automático) preservam origem0. Até10.000 registros por partição, incluindo reentregas; ultrapassar orçamento interrompe sem liberar reservas. Retenção integral não autoriza operação indefinida: encerramento/arquivamento e orçamento contínuo permanecem na RF05c. A implementação verifica origem, identidade do tópico e limites antes de refazer o livro; lacuna ou recriação não vira livro vazio silenciosamente.

Uma instância ME detém a posse por trava PostgreSQL mantida em conexão dedicada e atribui manualmente as partições. O processador é serial e a nova instância reconstrói desde origem0. Este laboratório não implementa rebalanceamento automático nem aumento de partições. Checkpoint por partição avança somente após ack de **todos** os eventos do comando; comandos sem eventos também avançam. Até o checkpoint, reconstruir sem republicar; depois, republicar o conjunto inteiro com os mesmos IDs. Falha de posse, publicação ou checkpoint interrompe e exige motor novo/replay. Prontidão financeira depende de replay concluído até a fronteira de partida, além da saúde técnica do banco/broker.

MO aplica os dois lados do negócio atomicamente, com deduplicação por evento e negócio. Compra de quantidade `q` ao preço `p` com máximo `P` reduz reservado em `qP`, total em `qp` e devolve disponível em `q(P−p)`; venda reduz total e reservado de custódia em `q`. Não há crédito inverso antes da RF08. Terminal valida executada/cancelada contra o progresso persistido e libera só saldo restante. Inconsistência gera diagnóstico sem payload e bloqueia o consumo sem avançar offset; erro financeiro não é descartado após dez tentativas. Preparação adquire o mesmo guardião de conta e recusa409 com qualquer reserva viva, incluindo legado/admissão pendente, sem apagar deduplicação.

FO pode receber execução antes do resultado do risco e deve aplicar desde `RECEBIDA` sem perder progresso. Eventos atualizam ambas as ordens numa transação, com quantidades executada/cancelada e transições monotônicas; resultado de risco tardio não regride estado. Quarentena bloqueia sequência para impedir terminal posterior de liberar recurso após negócio inconsistente. Origem textual é validação de contrato: Kafka PLAINTEXT ainda permite forja coerente, sem autenticidade criptográfica presumida.

Migrações aditivas dos serviços preservam fatos anteriores e não alteram migrações já aplicadas. Recuperação de falha exige corrigir por nova migração ou restaurar cópia privada previamente conferida; não há Undo pago nem eliminação automática de reservas. Após efeitos financeiros novos, voltar ao binário antigo não é recuperação segura. Validar upgrade com dados legados e schema novo em ambiente descartável antes de aplicar localmente.

## Encerramento do núcleo — RF05c.1a

O domínio recebe fechamento explícito por ativo e data, sem relógio nem I/O. Expira ofertas DIA vivas dos dois lados em ordem crescente da sequência de chegada, com `ORDEM_ENCERRADA{motivo=EXPIRADA_DIA,executada,cancelada}`: executada preserva negócios anteriores e cancelada corresponde somente ao saldo. Ordens já terminais não reaparecem. O fechamento marca a sessão encerrada mesmo sem ofertas ou negócios; um ativo conhecido ainda vazio também pode receber esse comando.

Fechar novamente uma data já encerrada é inócuo, inclusive depois de abrir uma data posterior. Fechamento de ativo desconhecido, data nula ou data divergente da sessão corrente é recusado sem alterar livro/fronteira, mesmo se ela já estiver encerrada. A repetição de data já encerrada tem precedência sobre essa comparação. Exceto no primeiro fechamento do ativo vazio, fechar uma data posterior exige antes admitir uma ordem válida nessa nova sessão. Admissão nova na data encerrada recebe `SESSAO_ENCERRADA`; data anterior à sessão atual recebe `SESSAO_ANTERIOR`; avanço sem fechamento recebe `SESSAO_NAO_ENCERRADA`. Uma ordem já vista continua inócua em qualquer ativo/data. A fronteira e os IDs históricos permanecem em memória: encerramento não autoriza expurgo nem reaproveitamento de identidade. Nova sessão após fechar nasce sem ofertas, com base de referência e sequências de chegada/negócios reiniciadas.

Esta fatia é interna. O contrato Kafka v1 continua admitindo somente ordens simples Limit/Market, sem fechamento, Stop ou OCO. Não publica `EXPIRADA_DIA` no ambiente integrado e não comprova liberação real de reservas. Fechamento integrado ainda exige comando de controle com autoridade/correlação próprias, roteado por ativo no mesmo log, evolução coordenada MO/FO e provas de publicação parcial/replay. Encerramento global não é atômico entre partições. Mantêm-se uma sessão por log, retenção integral e limites atuais; nenhuma migração ou limpeza de dados nesta fatia.

`[DOC-GAP]` A RF05c seguinte depende da regra para a 33ª ativação de stop e do momento de interleaving com o agressor, ainda aguardando decisão do usuário. OCO exige admissão atômica do par, saldo compartilhado e progresso por perna; reduzir a irmã não é executar nem liberar toda a reserva. Esses fluxos permanecem planejados.

## Segurança, observabilidade e testes

Seguir ADR-003 para DTOs, versão, Swagger, status e erros; contratos de negócio ainda precisam de definição. Autenticação e autorização por conta/papel são pendências, sem RBAC presumido. Não registrar credenciais ou payloads sensíveis. Entradas exigem limites e invariantes; o endpoint técnico não comprova proteção do fluxo financeiro.

Fundação: saúde de banco/Kafka, métricas técnicas, logs com correlação e exportação OTLP. Não afirmar trace financeiro completo, métricas de domínio ou capacidade comprovada. Testes atuais usam JUnit/REST Assured/ArchUnit; perfil de contrato desativa banco/Kafka, smoke Compose exercita dependências reais. Casos futuros via API/planilha conforme ADR-004 complementam invariantes internas e testes de concorrência/recuperação.

## Decisões e rastreabilidade

[ADR-001](../adrs/ADR-001-arquitetura-backend.md) define fronteiras/backend; [ADR-002](../adrs/ADR-002-padroes-dominio.md), padrões; [ADR-003](../adrs/ADR-003-contratos-api.md), contratos; [ADR-004](../adrs/ADR-004-testes-planilha.md), testes; [ADR-005](../adrs/ADR-005-versoes-fundacao.md), stack. [PRD-001](../prds/PRD-001-simulador-prospero.md) é a fonte dos requisitos. Este HLD detalha o [ecossistema](HLD-001-arquitetura-prospero.md), sem alterar decisões.
