# Roadmap — Angular

## Responsabilidade e estado

Apresentar dados e operações do investidor, consumindo APIs versionadas. O projeto também hospeda o runner de planilhas, sem transformá-lo em funcionalidade de importação pela interface. Este documento detalha a participação nas tarefas do [roadmap global](../docs/roadmap.md).

A fundação P02 foi validada localmente e integrada pelo PR #1: Angular 22.2.3, TypeScript estrito, painel responsivo, consulta real de identidade/readiness dos quatro serviços, estados de falha independentes e container Nginx. RF01 acrescentou a consulta de ativos e livro; RF02 a boleta de registro de ordens (simples e OCO) com lista da conta demo. RF03/RF04 exibem recusas de validação/risco e reserva; RF05 acompanha execução e, na RF05c.2, espera pelo gatilho de stops. RF05 integrada até o PR #36 inclui OCO e robô. RF06a integrada pelo PR #37 projeta cotação real; RF06b integrada no PR #38 implementa polling e VWAP visual; RF07 acrescenta a seção de posições/PnL em validação. Evidências e PRs no roadmap global; liquidação permanece pendente.

## Entregas

| ID local | Tarefa global | Entrega | Estado |
| --- | --- | --- | --- |
| UI00 | P02 | Aplicação inicial, proxy, painel técnico e container | Concluída — PR #1 integrado |
| UI01 | P03 | Runner CLI `.xlsx`, exemplos e relatório via API | Concluída — PR #9 integrado em 2026-10-17; 49 testes runner + exemplo real validados |
| UI02 | RF01 | Lista dos cinco ativos, cotações e profundidade | Concluída — PR #17 integrado |
| UI03 | RF02 | Boleta, tipos/validades e acompanhamento de submissão | Concluída — PR #19 integrado |
| UI04 | RF03, RF04 | Exibir validações, rejeições, reservas e poder de compra | RF03/RF04 integradas (recusas e estado reservado); carteira do MO disponível por API, sem tela de poder de compra |
| UI05 | RF05, RF06 | Livro, parciais e atualizações de mercado | RF05/RF06 integradas; polling independente e VWAP visual disponíveis |
| UI06 | RF07, RF08 | Posições, resultados, histórico e liquidação | Posições/PnL em validação RF07; histórico e liquidação RF08 planejados |
| UI07 | RF09 | WebSocket, reconexão e trajetória da ordem | Planejada |
| UI08 | RF10 | Cancelamento e confirmação do saldo elegível | Planejada |
| UI99 | V01 | Acessibilidade, responsividade e cenário completo sob carga | Planejada |

## Aceite e comandos

Neste diretório: `npm ci`, `npm test -- --watch=false`, `npm run build`, `npm run format:check` e `npm start`. O dev server usa proxy para os serviços locais nas portas 8081–8084. Em Compose, o Nginx resolve os serviços por DNS e serve a mesma origem em `http://localhost:4200`.

As telas devem distinguir carregamento, vazio, sucesso e erro, funcionar por teclado e não depender apenas de cor. Nenhuma ordem é repetida automaticamente sem idempotência definida. Tratar corretamente respostas sem corpo e processamento assíncrono conforme o [ADR-003](../docs/adrs/ADR-003-contratos-api.md).

As planilhas seguirão o [ADR-004](../docs/adrs/ADR-004-testes-planilha.md); seus dados, passos e verificações usarão exclusivamente APIs. Toda alteração visual inclui evidência no PR da tarefa global, sem duplicar o fluxo de entrega por componente.
