# P06 — Carga progressiva e observabilidade

## Objetivo e limites

Planejamento autorizado, ainda sem implementação. Medir concorrência e saturação local com múltiplos usuários virtuais e requisições, preservando recursos dos demais projetos. O artigo de [Marcelo M. Gonçalves](https://medium.com/@marcelomg21/sistemas-distribu%C3%ADdos-observabilidade-telemetria-e-monitoramento-46d579d050b1) fundamenta a combinação de logs, métricas e traces; não define nossa topologia nem comprova capacidade.

Antes do código, invocar refinar-task e avaliar PRD/HLD/ADRs proporcionais. Definir localização do controlador técnico, motor de geração, coletores, autenticação/proteção do controle e orçamento. Nenhuma ferramenta adicional foi adotada nesta tarefa.

## Controle por API

Planejar endpoints `/api/v1` com Swagger, DTOs e recurso de execução: iniciar, consultar e cancelar. `202` com identificador e Location é candidato para início assíncrono, sujeito ao contrato ADR-003; aceitação não significa conclusão. Estados previstos: aguardando, executando, concluída, cancelada e falha.

Separar controlador e gerador do processamento financeiro. Uma execução ativa global, estágios com teto de duração, usuários virtuais, RPS e concorrência; timeouts, cancelamento manual, interrupção automática e encerramento do gerador se o controlador falhar. Limitar requisições totais, conexões, filas pendentes, corpo de resposta e retenção.

Usar alvos por identificador de serviço e rotas permitidas. Não aceitar URLs, portas, comandos, redirecionamentos ou headers arbitrários. Proteger o controle contra chamadas indevidas do navegador mesmo em loopback. Preparação, tráfego e limpeza de dados exclusivamente por APIs, restritos à execução; não semear bancos/Kafka diretamente nem apagar volumes.

## Modelo de carga e contabilização

Distinguir chegada aberta (RPS) de usuários fechados (VUs). Registrar oferecidas/agendadas, iniciadas, concluídas, em voo, erros HTTP, timeouts, nunca iniciadas e descartadas pelo gerador. Conservar contagens com janela explícita e distinguir resultados de transporte e negócio.

Medir espera do agendador e saturação do gerador para evitar ocultar atrasos. Relatar aquecimento, duração, relógio, amostras, denominadores e tratamento de erros nos percentis p50/p95/p99 e máximo. Usuários virtuais não comprovam isolamento de usuários autenticados.

## Fontes e dashboards Grafana

| Área | Medição prevista | Situação atual |
| --- | --- | --- |
| HTTP/carga | Vazão oferecida/atingida, falhas, timeouts, descartes e latência | Coleta Quarkus básica; gerador não implementado |
| Recursos | CPU, memória, GC, disco, rede, descritores, pool de conexões | Definir coletores de host/container/DB; medir gerador separadamente |
| Filas/eventos | Consumer lag, idade do backlog/outbox e propagação de eventos | Definir coletores e implementar fluxos financeiros primeiro |
| Diagnóstico | Logs de erros e traces correlacionados | OTel/Loki/Jaeger presentes na fundação |
| Consistência | Defasagem de projeções; replicação/consenso se aplicável | Projeções financeiras futuras; replicação e consenso distribuído N/A |

Provisionar painéis técnicos dessas áreas, complementando os quatro dashboards de negócio da V01. Dados ausentes aparecem como ausentes, nunca zero. Os quatro PostgreSQL são independentes, e Kafka possui um broker KRaft/RF1: isso não demonstra replicação nem consenso entre bancos. Uma topologia replicada opcional depende de ADR e recursos adicionais.

Métricas usam labels limitadas a serviço, rota, método, status e fase. IDs de usuário, requisição ou execução pertencem à correlação em logs/traces/anotações, sem virar labels de alta cardinalidade. Aplicar limites de retenção.

## Execução e aceite futuro

Fazer preflight de RAM, CPU e disco, incluindo gerador e telemetria; abortar quando não houver orçamento seguro. Não interromper Horus nem iniciar outra stack para obter números. Não executar stress nesta tarefa de planejamento.

Etapa A: baseline técnico dos endpoints existentes, sem alegar capacidade financeira. Etapa B: cenários de negócio após RFs e runner P03, com preparação e limpeza via API. V01 depende da P06 e RF01–RF10; registrar hardware, configuração e evidências reproduzíveis.

Referência conceitual adicional: [sinais OpenTelemetry](https://opentelemetry.io/docs/concepts/signals/). Fontes específicas de métricas e ferramentas serão verificadas no refinamento de implementação, sem inventar coletores já instalados.
