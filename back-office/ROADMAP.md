# Roadmap — Back office

## Responsabilidade e estado

Registrar negócios, calcular posições e resultados e simular liquidação. Comunicação com outros serviços somente por Kafka; persistência exclusiva em `postgres-back-office`, banco/usuário `prospero_bo`. Este roteiro detalha a participação do componente nas tarefas do [roadmap global](../docs/roadmap.md); não cria PRs duplicados para o mesmo RF.

A fundação P02 foi validada localmente e integrada pelo PR #1: Quarkus/Java 26, build independente, Flyway, JPA configurado, DTO de identidade, saúde e OpenAPI. RF07 integrada pelo PR #42 acrescentou projeção idempotente de negócios/posições/cotação, API autenticada e migração V2 aditiva; a RF08b (integrada pelo PR #45) acrescenta V3 com fatos econômicos, razão D0 append-only, liquidação pendente e `GET /api/v1/negocios`/`/api/v1/razao` (escopo `razao:leitura`); RF08d implementa data de movimento, liquidação D+2 em lotes e outbox no [PR #47](https://github.com/mclovin137/Prospero/pull/47), aguardando revisão/merge; conciliação e tela continuam RF08e.

## Entregas

| ID local | Tarefa global | Entrega | Estado |
| --- | --- | --- | --- |
| BO00 | P02 | Build, container, banco próprio e endpoints técnicos | Concluída — PR #1 integrado |
| BO01 | RF07 | Calcular long/short, preço médio, reduções e viradas. | Concluída — PR #42 integrado |
| BO02 | RF07 | Atualizar resultado não realizado com eventos de cotação. | Concluída — PR #42 integrado |
| BO03 | RF08 | Persistir negócio, razão e posição atomicamente e com idempotência. | RF08b concluída — PR #45 integrado (V3, razão D0, consultas) |
| BO04 | RF08 | Liquidar em D+2 com calendário, controle de tempo de teste e conciliação. | RF08d no PR #47, aguardando revisão/merge; conciliação RF08e planejada |
| BO05 | RF09 | Publicar os estados que compõem a trajetória da ordem. | Planejada |
| BO99 | V01 | Resiliência, métricas, rastreio e critérios não funcionais aplicáveis | Planejada |

## Verificação e execução

Com JDK 26, execute neste diretório `./mvnw verify`. Os testes de contrato usam HTTP e desativam banco/Kafka apenas no perfil de testes; a validação real das dependências pertence ao smoke test do Compose.

Na raiz: `docker compose up -d --build back-office` sobe também seu banco, Kafka e inicialização dos tópicos. Não exige outro microsserviço Java. O endpoint `http://localhost:8084/api/v1/sistema` informa identidade/etapa; `/api/v1/posicoes` exige JWT BO com `posicoes:leitura` e conta no subject; `/q/health/ready` inclui projeção sincronizada, além de banco/Kafka; `/q/swagger-ui/` documenta a API; `/q/metrics` expõe métricas. O processo pode estar vivo e ainda não estar pronto.

Cada entrega segue os [ADRs](../docs/adrs/README.md), inclui testes por API, planilha quando elegível e resumo de alterações, motivos e trade-offs no PR da tarefa global. Regras financeiras e escalas precisam estar explícitas antes da implementação correspondente.
