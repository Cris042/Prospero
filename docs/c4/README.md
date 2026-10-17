# Diagramas de arquitetura do Próspero

Seis recortes no README: os quatro fluxos arquiteturais substituem as figuras antigas, e dois modelos didáticos explicam domínio e formação de preço. Estrutura usa notação C4 local; Kafka/SAGA são vistas dinâmicas complementares, sem promover sequência a componente implementado. Fontes PlantUML autocontidas, sem biblioteca/includes remotos. O agente do [perfil C4](../agentes/c4-diagram-generator.md) propôs texto em leitura; edição e validação pertencem ao orquestrador e revisores.

| Recorte | Imagem | Fonte editável | Escopo |
| --- | --- | --- | --- |
| Serviços e bancos | [SVG](arquitetura-servicos.svg) | [PUML](arquitetura-servicos.puml) | Fundação configurada; responsabilidades financeiras rotuladas como planejadas. |
| Kafka e recuperação | [SVG](kafka-recuperacao.svg) | [PUML](kafka-recuperacao.puml) | Configuração atual, consumidores futuros e três falhas ilustrativas. |
| SAGA e consistência | [SVG](saga-consistencia.svg) | [PUML](saga-consistencia.puml) | Fluxo futuro, rejeição, compensação, resultado desconhecido e recuperação para frente. |
| Observabilidade | [SVG](observabilidade.svg) | [PUML](observabilidade.puml) | Rotas atuais de sinais e indicadores a implementar/monitorar. |
| Modelo de domínio | [SVG](modelo-conceitual.svg) | [PUML](modelo-conceitual.puml) | Ordem, reserva, livro, execução, negócio, posição e razão por contexto; não é schema/C3 implementado. |
| Formação de preço | [SVG](livro-market-exemplo.svg) | [PUML](livro-market-exemplo.puml) | Compra fictícia de 0,10 consumindo três níveis; sem teste executado ou escala de produto aprovada. |

## Fontes e limites

- Estrutura/Kafka: [Compose](../../docker-compose.yml), [Nginx](../../frontend/nginx.conf), [configuração FO](../../front-office/src/main/resources/application.properties) e equivalentes dos outros serviços. Kafka único, auto-create desativado, sem grupos/consumidores financeiros no código. Os quatro tópicos candidatos continuam no [HLD-001](../hlds/HLD-001-arquitetura-prospero.md); não são contratos provisionados. PLAINTEXT local não implica TLS/ACL. Volume não garante recuperação após perda de dados.
- Consistência: [ADR-002](../adrs/ADR-002-padroes-dominio.md), [FO](../hlds/HLD-002-front-office.md), [MO](../hlds/HLD-003-middle-office.md), [ME](../hlds/HLD-004-matching-engine.md) e [BO](../hlds/HLD-005-back-office.md). SAGA/outbox/deduplicação são destino. Timeout não autoriza liberar reserva; transação Kafka não reverte memória nem inclui banco externo. Reentrega/retomada exigem estado coerente e retenção suficiente. Diagramas não definem novos contratos, estados públicos ou prazos.
- Telemetria: [Collector](../../infra/otel/collector.yaml), [Prometheus](../../infra/prometheus/prometheus.yml), [datasources](../../infra/grafana/provisioning/datasources/fontes.yaml) e [dashboard Fundação](../../infra/grafana/dashboards/fundacao.json). Painel atual contém up por serviço; métricas financeiras e coletores detalhados continuam planejados. up mede scrape, não saúde financeira.

**Simulação é ilustrativa:** nenhum comando de derrubada, ensaio de falha, replay financeiro, medição ou execução da stack acompanha esta revisão. Nós/setas de destino não declaram implementação. Reinício pode perder traces Jaeger; nó único Kafka não oferece HA. O [README](../../README.md) explica tópicos, responsabilidades dos consumidores e indicadores sem inventar nomes de grupos, partições, retries, DLQ ou séries financeiras.

## Referências visuais e exemplos

As quatro imagens distintas fornecidas pelo usuário inspiraram a apresentação: conceitos de mercado; requisitos e temas de estudo; papéis dos escritórios e modelo/eventos; Kafka e SAGA. Não foram copiadas para o repositório. Os diagramas são autorais e seguem PRD/HLD/ADRs locais: não importam Spring/JDBC, Java 25, Redis no BO, novos tópicos/grupos ou fórmulas de reserva das referências.

O [PRD](../prds/PRD-001-simulador-prospero.md) e HLDs sustentam os conceitos, mas não aprovam novos schemas. O cenário de livro acrescenta preços/quantidades hipotéticos explícitos: 0,05 × 100,00 + 0,03 × 100,10 + 0,02 × 100,30 = 10,009; média desta compra = 100,09. Sobra 0,02 do nível originalmente com 0,04; último 100,30 representa +0,3% contra 100,00. Não é VWAP do dia, preço médio total de posição nem medição real. O lado bid foi omitido; não inferir spread do recorte.

## Manutenção e renderização local

Manter seis pares PUML/SVG úteis, sem cópias de capturas ou relatório paralelo. Atualizar fonte e imagem juntas, conferir fontes/legendas e inspecionar leitura. PlantUML 1.2025.2 é ferramenta temporária opcional de documentação, fora das dependências do produto; Smetana nas vistas estruturais dispensa Graphviz. Sem envio a renderizador externo:

```bash
java -jar /caminho/local/plantuml-1.2025.2.jar -tsvg -charset UTF-8 docs/c4/*.puml
```

Verificação proporcional: links, XML/SVG sem conteúdo ativo, correspondência com fontes, renderização e legibilidade; resultados no PR, sem pasta de relatórios. Não criar níveis C4 adicionais sem benefício e evidência.
