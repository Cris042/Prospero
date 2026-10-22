# Roadmap — Front office

## Responsabilidade e estado

Receber comandos HTTP, persistir o estado das ordens e orquestrar a SAGA. Comunicação com outros serviços somente por Kafka; persistência exclusiva em `postgres-front-office`, banco/usuário `prospero_fo`. Este roteiro detalha a participação do componente nas tarefas do [roadmap global](../docs/roadmap.md); não cria PRs duplicados para o mesmo RF.

A fundação P02 foi integrada pelo PR #1. RF01–RF04 acrescentaram mercado, ordens idempotentes, validação e SAGA de risco. A RF05b acrescenta o consumidor/progresso de matching; evidências e PRs no roadmap global.

## Entregas

| ID local | Tarefa global | Entrega | Estado |
| --- | --- | --- | --- |
| FO00 | P02 | Build, container, banco próprio e endpoints técnicos | Concluída — PR #1 integrado |
| FO01 | RF01 | Consultar ativos/cotações e expor o livro projetado. | Concluída — PR #17 integrado |
| FO02 | RF02 | Enviar ordens, DTOs, idempotência de submissão e consulta de estado. | Concluída — PRs #18/#19 integrados |
| FO03 | RF03, RF04 | Encaminhar validação/risco e persistir transições e compensações da SAGA. | Concluída — PRs #20–#22 |
| FO04 | RF05, RF06 | Consumir execuções e atualizar a visão das ordens/cotações via eventos. | RF05 integrada até PR #36; cotação real RF06a integrada no PR #37; apresentação contínua RF06b em validação |
| FO05 | RF09 | Disponibilizar linha do tempo e atualizações WebSocket. | Planejada |
| FO06 | RF10 | Receber cancelamento e acompanhar confirmação/liberação. | Planejada |
| FO99 | V01 | Resiliência, métricas, rastreio e critérios não funcionais aplicáveis | Planejada |

## Verificação e execução

Com JDK 26, execute neste diretório `./mvnw verify`. Os testes de contrato usam HTTP e desativam banco/Kafka apenas no perfil de testes; a validação real das dependências pertence ao smoke test do Compose.

Na raiz: `docker compose up -d --build front-office` sobe também seu banco e Kafka. Não exige outro microsserviço Java. O endpoint `http://localhost:8081/api/v1/sistema` informa identidade/etapa; `/q/health/ready` verifica banco e Kafka; `/q/swagger-ui/` documenta a API; `/q/metrics` expõe métricas. O processo pode estar vivo e ainda não estar pronto.

Cada entrega segue os [ADRs](../docs/adrs/README.md), inclui testes por API, planilha quando elegível e resumo de alterações, motivos e trade-offs no PR da tarefa global. Regras financeiras e escalas precisam estar explícitas antes da implementação correspondente.
