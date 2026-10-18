# HLD-004 — Matching engine

Data: 2026-10-17. Desenho vigente e planejado do contexto, sem nova decisão de produto.

## Responsabilidade e estado

Contexto de execução: RF05, publicação de mercado RF06 e cancelamento RF10. Desde a RF05a ([ADR-013](../adrs/ADR-013-matching-execucao.md)) existe o núcleo puro `livro/dominio/MotorMatching` (livro por ativo com `TreeMap` e filas FIFO, Limit/Market, DIA/IOC/FOK, preço passivo, faixa da Market e limite pela reserva, IDs `TICKER-sessão-n`), sem Kafka; adaptador, replay e stops seguem nas fatias RF05b–RF05c. RNF02/RNF03 e metas RNF04 ainda exigem provas.

Desenho de integração: um escritor por ativo, sequenciado pela partição Kafka; no núcleo atual, um escritor por instância de `MotorMatching`, mesmo quando contém vários ativos. O núcleo puro recebe comando e produz eventos. Não consulta JPA/banco por ordem nem decide poder de compra. Stops ficam no ME como simplificação já descrita no domínio; risco e reservas precedem admissão ao matching.

## Stack e organização

Java 26, Quarkus 3.40.1, Maven Wrapper, REST Jackson/Bean Validation, JPA/Hibernate, PostgreSQL 16 e Flyway; cliente Kafka, SmallRye OpenAPI, Health, Micrometer e OpenTelemetry. Versões resolvidas/imagens e overrides: [libs](../libs.md) e ADR-005. Build e imagem próprios, sem artefato compartilhado entre serviços. Não há dependência de implementação de outro contexto.

Organização por funcionalidade sob `br.prospero.me`: REST → Actor; Actor coordena domínio/Helpers puros e repositórios JPA separados. Adaptadores Kafka futuros chamam casos de uso sem passar pelo REST. Entidades JPA não são DTOs nem domínio. Pacotes vazios não são componentes de negócio implementados.

## Interfaces e implantação atuais

`GET /api/v1/sistema` retorna identidade técnica. Porta externa `8083` em loopback → `8080` no container; `/q/health/ready`, `/q/openapi`, `/q/swagger-ui/` e `/q/metrics` são técnicos. Configuração usa variáveis locais fora do Git. Banco/container exclusivo `postgres-matching-engine`, banco `prospero_me`, rede de dados própria. Não acessar tabelas de outro serviço.

`src/main/resources/db/migration/V1__identificar_schema.sql` só identifica o schema. Flyway mantém histórico, sem tabelas financeiras. Build com JDK26: `./mvnw verify` no diretório do serviço; operação pela skill subir-sistema. Processo pode iniciar sem outro MS Java, mas depende de sua infraestrutura para ficar pronto; não confundir autonomia com conclusão de negócio sem participantes.

## Livro e processamento planejados

Na RF05a, `Livro` usa `TreeMap` por preço, `ArrayDeque` FIFO por nível, índice por ID e contagem de ativas por conta. `MotorMatching` recebe `Admissao` e devolve records de `Evento`; stops por gatilho e o processador de partição permanecem planejados. Não prometer cancelamento O(1) sem definir fila/índice; medir antes de otimizar.

Comando autorizado → prioridade preço-tempo → negócios a preço passivo, parciais e eventos de estado → publicação recuperável. Definir Market/Limit/Stop/Stop Limit, Dia/IOC/FOK, verificação integral FOK, falta de liquidez, cascatas e OCO após parciais. Inserção e cancelamento sem negócio também alteram bid/ask/profundidade.

## Persistência, recuperação e pendências

O núcleo atual mantém o livro apenas em memória. Na RF05b, o log Kafka permitirá reconstrução e o PostgreSQL próprio guardará o último offset publicado, conforme ADR-013. Snapshots ficam adiados até haver necessidade medida. Não compartilhar banco/entidade e não criar tabela do livro no caminho crítico.

Na RF05b, comprovar retenção suficiente, replay e rebalanceamento com posse exclusiva dos livros, sem transações Kafka. Publicação interrompida exige reconstrução e deduplicação com IDs determinísticos antes de declarar recuperação segura. Relógio, desempate e IDs não podem mudar negócios no replay.

Na RF05a, reentregas de IDs já decididos não geram eventos, inclusive quando o ID reaparece em outro ativo. FOK verifica a execução integral dentro do teto de 256 negócios antes de tocar o livro. A Market distingue corte pelo túnel de corte pelo preço reservado. A troca de data reinicia a base e a sequência de negócios e recusa datas anteriores; o encerramento, a expiração de ofertas DIA e o descarte de deduplicação são entregas da RF05c. Até lá, instâncias de laboratório devem representar uma sessão e não comprovar ciclo diário completo. Os testes comparam também duas datas para verificar IDs e base, sem provar expiração.

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

## Segurança, observabilidade e testes

Seguir ADR-003 para DTOs, versão, Swagger, status e erros; contratos de negócio ainda precisam de definição. Autenticação e autorização por conta/papel são pendências, sem RBAC presumido. Não registrar credenciais ou payloads sensíveis. Entradas exigem limites e invariantes; o endpoint técnico não comprova proteção do fluxo financeiro.

Fundação: saúde de banco/Kafka, métricas técnicas, logs com correlação e exportação OTLP. Não afirmar trace financeiro completo, métricas de domínio ou capacidade comprovada. Testes atuais usam JUnit/REST Assured/ArchUnit; perfil de contrato desativa banco/Kafka, smoke Compose exercita dependências reais. Casos futuros via API/planilha conforme ADR-004 complementam invariantes internas e testes de concorrência/recuperação.

## Decisões e rastreabilidade

[ADR-001](../adrs/ADR-001-arquitetura-backend.md) define fronteiras/backend; [ADR-002](../adrs/ADR-002-padroes-dominio.md), padrões; [ADR-003](../adrs/ADR-003-contratos-api.md), contratos; [ADR-004](../adrs/ADR-004-testes-planilha.md), testes; [ADR-005](../adrs/ADR-005-versoes-fundacao.md), stack. [PRD-001](../prds/PRD-001-simulador-prospero.md) é a fonte dos requisitos. Este HLD detalha o [ecossistema](HLD-001-arquitetura-prospero.md), sem alterar decisões.
