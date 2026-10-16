# Roadmap — Matching engine

## Responsabilidade e estado

Manter o livro em memória e produzir negócios determinísticos. Comunicação com outros serviços somente por Kafka; persistência exclusiva em `postgres-matching-engine`, banco/usuário `prospero_me`. Este roteiro detalha a participação do componente nas tarefas do [roadmap global](../docs/roadmap.md); não cria PRs duplicados para o mesmo RF.

A fundação P02 foi validada localmente e integrada pelo PR #1: Quarkus/Java 26, build independente, Flyway, JPA configurado, DTO de identidade, saúde e OpenAPI. Não há entidades nem endpoints de negócio implementados. A migração inicial identifica o schema e cria o histórico Flyway, sem inventar tabelas financeiras.

## Entregas

| ID local | Tarefa global | Entrega | Estado |
| --- | --- | --- | --- |
| ME00 | P02 | Build, container, banco próprio e endpoints técnicos | Concluída — PR #1 integrado |
| ME01 | RF05 | Implementar domínio puro: livro, preço-tempo, FIFO e execuções parciais. | Planejada |
| ME02 | RF05 | Implementar Market/Limit/Stop/Stop Limit, IOC/FOK e OCO. | Planejada |
| ME03 | RF05 | Integrar Kafka por ativo, snapshots, offsets e replay após falhas. | Planejada |
| ME04 | RF06 | Publicar negócios e mudanças de ofertas mesmo sem execução. | Planejada |
| ME05 | RF10 | Cancelar apenas saldo elegível e ordenar cancelamento com execução. | Planejada |
| ME99 | V01 | Resiliência, métricas, rastreio e critérios não funcionais aplicáveis | Planejada |

## Verificação e execução

Com JDK 26, execute neste diretório `./mvnw verify`. Os testes de contrato usam HTTP e desativam banco/Kafka apenas no perfil de testes; a validação real das dependências pertence ao smoke test do Compose.

Na raiz: `docker compose up -d --build matching-engine` sobe também seu banco e Kafka. Não exige outro microsserviço Java. O endpoint `http://localhost:8083/api/v1/sistema` informa identidade/etapa; `/q/health/ready` verifica banco e Kafka; `/q/swagger-ui/` documenta a API; `/q/metrics` expõe métricas. O processo pode estar vivo e ainda não estar pronto.

Cada entrega segue os [ADRs](../docs/adrs/README.md), inclui testes por API, planilha quando elegível e resumo de alterações, motivos e trade-offs no PR da tarefa global. Regras financeiras e escalas precisam estar explícitas antes da implementação correspondente.
