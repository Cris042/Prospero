# HLD-004 — Matching engine

Data: 2026-10-17. Desenho vigente e planejado do contexto, sem nova decisão de produto.

## Responsabilidade e estado

Contexto de execução: RF05, publicação de mercado RF06 e cancelamento RF10. Fundação técnica e banco de suporte existem; não há livro, algoritmo, replay, snapshots ou processamento financeiro implementados. RNF02/RNF03 e metas RNF04 ainda exigem provas.

Desenho: um escritor por ativo, sequenciado pela partição Kafka; núcleo puro recebe comando e produz eventos. Não consulta JPA/banco por ordem nem decide poder de compra. Stops ficam no ME como simplificação já descrita no domínio; risco e reservas precedem admissão ao matching.

## Stack e organização

Java 26, Quarkus 3.40.1, Maven Wrapper, REST Jackson/Bean Validation, JPA/Hibernate, PostgreSQL 16 e Flyway; cliente Kafka, SmallRye OpenAPI, Health, Micrometer e OpenTelemetry. Versões resolvidas/imagens e overrides: [libs](../libs.md) e ADR-005. Build e imagem próprios, sem artefato compartilhado entre serviços. Não há dependência de implementação de outro contexto.

Organização por funcionalidade sob `br.prospero.me`: REST → Actor; Actor coordena domínio/Helpers puros e repositórios JPA separados. Adaptadores Kafka futuros chamam casos de uso sem passar pelo REST. Entidades JPA não são DTOs nem domínio. Pacotes vazios não são componentes de negócio implementados.

## Interfaces e implantação atuais

`GET /api/v1/sistema` retorna identidade técnica. Porta externa `8083` em loopback → `8080` no container; `/q/health/ready`, `/q/openapi`, `/q/swagger-ui/` e `/q/metrics` são técnicos. Configuração usa variáveis locais fora do Git. Banco/container exclusivo `postgres-matching-engine`, banco `prospero_me`, rede de dados própria. Não acessar tabelas de outro serviço.

`src/main/resources/db/migration/V1__identificar_schema.sql` só identifica o schema. Flyway mantém histórico, sem tabelas financeiras. Build com JDK26: `./mvnw verify` no diretório do serviço; operação pela skill subir-sistema. Processo pode iniciar sem outro MS Java, mas depende de sua infraestrutura para ficar pronto; não confundir autonomia com conclusão de negócio sem participantes.

## Livro e processamento planejados

A referência propõe bids em árvore ordenada decrescente e asks crescente, FIFO por nível, índice de ordens por ID e stops por gatilho. `TreeMap`, hierarquia fechada de eventos/records e nomes `OrderBook`, `MatchingEngine` e `ProcessadorDeParticao` são candidatos, não classes presentes nem decisão de estrutura física final. Não prometer cancelamento O(1) sem definir fila/índice; medir antes de otimizar.

Comando autorizado → prioridade preço-tempo → negócios a preço passivo, parciais e eventos de estado → publicação recuperável. Definir Market/Limit/Stop/Stop Limit, Dia/IOC/FOK, verificação integral FOK, falta de liquidez, cascatas e OCO após parciais. Inserção e cancelamento sem negócio também alteram bid/ask/profundidade.

## Persistência, recuperação e pendências

Livro em memória derivado do log; snapshots aceleram reconstrução. PostgreSQL próprio fica reservado para suporte a definir, sem tabela inventada para justificar existência. Não compartilhar banco/entidade e não criar tabela do livro no caminho crítico.

Definir formato/versão do snapshot e offset consistente; retenção suficiente, replay e rebalanceamento com posse exclusiva dos livros. Uma transação Kafka abortada não desfaz mutação em memória: definir reconstrução/retomada antes de declarar recuperação segura. Relógio, desempate e IDs determinísticos não podem mudar negócios no replay.

Catálogo de comandos/eventos, chaves e passagem para ordenação por conta precisam de desenho conjunto. Testes futuros: mesma sequência/mesmos negócios, conservação, preço passivo, livro não cruzado ao final do processamento, stops/OCO, corrida cancelamento/execução e falhas em publicação/rebalanceamento. A referência histórica de ordem `0,10` atravessando três níveis não fornecia preços/quantidades suficientes. O [exemplo didático do README](../../README.md#impacto-de-mercado-uma-ordem-pequena-atravessa-o-livro) agora explicita um conjunto hipotético; não é teste de aceite executado nem definição de lote/tick/escala financeira.

Ativo quente limita throughput de sua sequência; partições/GC/alocação exigem medição. JFR/JMH/async-profiler, ZGC e bibliotecas de propriedades são alternativas não adotadas por este HLD. [Roadmap ME](../../matching-engine/ROADMAP.md); RNF01–RNF04/RNF06–RNF08.

## Segurança, observabilidade e testes

Seguir ADR-003 para DTOs, versão, Swagger, status e erros; contratos de negócio ainda precisam de definição. Autenticação e autorização por conta/papel são pendências, sem RBAC presumido. Não registrar credenciais ou payloads sensíveis. Entradas exigem limites e invariantes; o endpoint técnico não comprova proteção do fluxo financeiro.

Fundação: saúde de banco/Kafka, métricas técnicas, logs com correlação e exportação OTLP. Não afirmar trace financeiro completo, métricas de domínio ou capacidade comprovada. Testes atuais usam JUnit/REST Assured/ArchUnit; perfil de contrato desativa banco/Kafka, smoke Compose exercita dependências reais. Casos futuros via API/planilha conforme ADR-004 complementam invariantes internas e testes de concorrência/recuperação.

## Decisões e rastreabilidade

[ADR-001](../adrs/ADR-001-arquitetura-backend.md) define fronteiras/backend; [ADR-002](../adrs/ADR-002-padroes-dominio.md), padrões; [ADR-003](../adrs/ADR-003-contratos-api.md), contratos; [ADR-004](../adrs/ADR-004-testes-planilha.md), testes; [ADR-005](../adrs/ADR-005-versoes-fundacao.md), stack. [PRD-001](../prds/PRD-001-simulador-prospero.md) é a fonte dos requisitos. Este HLD detalha o [ecossistema](HLD-001-arquitetura-prospero.md), sem alterar decisões.
