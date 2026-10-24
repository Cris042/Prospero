# Roadmap — Middle office

## Responsabilidade e estado

Validar parâmetros, controlar exposição e manter reservas atômicas. Comunicação com outros serviços somente por Kafka; persistência exclusiva em `postgres-middle-office`, banco/usuário `prospero_mo`. Este roteiro detalha a participação do componente nas tarefas do [roadmap global](../docs/roadmap.md); não cria PRs duplicados para o mesmo RF.

A fundação P02 foi validada localmente e integrada pelo PR #1: Quarkus/Java 26, build independente, Flyway, JPA configurado, DTO de identidade, saúde e OpenAPI. A RF04a acrescenta o consumidor Kafka de risco, reservas atômicas e outbox (ADR-012); a RF04b expõe a carteira da conta e a preparação local de carteiras de teste.

## Entregas

| ID local | Tarefa global | Entrega | Estado |
| --- | --- | --- | --- |
| MO00 | P02 | Build, container, banco próprio e endpoints técnicos | Concluída — PR #1 integrado |
| MO00A | P05 | Conversão decimal exata técnica e limites, sem regras financeiras antecipadas. | Concluída — PR #3 integrado |
| MO01 | RF03 | Validar lote, tick, túnel e preços de referência. | Lote/tick/túnel no FO (ADR-011, PR #20); MO revalida lote e quantidade máxima na RF04a |
| MO02 | RF04 | Reservar recursos e proteger limites contra concorrência. | Concluída — PRs #21/#22 integrados |
| MO03 | RF05 | Admitir ordens reservadas, consumir execuções e ajustar reservas; preparação recusa conta viva. | RF05b e integração de stops simples RF05c.2 em validação |
| MO04 | RF08 | Receber efeitos de liquidação por eventos e atualizar os saldos locais. | RF08c integrada pelo PR #44; RF08e valida conciliação, consulta por conta e guarda ativa |
| MO05 | RF10 | Liberar saldo elegível com deduplicação e proteção contra corridas. | Planejada |
| MO99 | V01 | Resiliência, métricas, rastreio e critérios não funcionais aplicáveis | Planejada |

## Verificação e execução

Com JDK 26, execute neste diretório `./mvnw verify`. Os testes de contrato usam HTTP e desativam banco/Kafka apenas no perfil de testes; a validação real das dependências pertence ao smoke test do Compose.

Na raiz: `docker compose up -d --build middle-office` sobe também seu banco e Kafka. Não exige outro microsserviço Java. O endpoint `http://localhost:8082/api/v1/sistema` informa identidade/etapa; `/q/health/ready` verifica banco e Kafka; `/q/swagger-ui/` documenta a API; `/q/metrics` expõe métricas. O processo pode estar vivo e ainda não estar pronto.

Cada entrega segue os [ADRs](../docs/adrs/README.md), inclui testes por API, planilha quando elegível e resumo de alterações, motivos e trade-offs no PR da tarefa global. Regras financeiras e escalas precisam estar explícitas antes da implementação correspondente.
