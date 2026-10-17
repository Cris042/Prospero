# PRD-001 — Simulador local de negociação Próspero

Data: 2026-10-17. Origem: requisitos definidos pelo usuário e consolidados nos documentos anteriores, migrados na P08. A extração organiza requisitos existentes; não representa aceite de funcionalidades ainda não implementadas. Estado e prioridades de entrega: [roadmap](../roadmap.md).

## Contexto, problema e objetivo

O universo do simulador contém **cinco ações fictícias**. Estudar como uma ordem percorre negociação, risco, execução, contabilização e liquidação, com estados e falhas observáveis em um ambiente local reproduzível. O produto permite aprender formação de preços e consistência financeira sem operar em bolsa ou usar dinheiro real.

## Usuários e atores

Investidor simulado consulta mercado, envia e acompanha ordens e posições. Participantes simulados fornecem ofertas; desenvolvedor/estudante prepara cenários e investiga trajetórias, erros e desempenho. Identidade/autorização dos atores de negócio ainda precisa de definição nas tarefas pertinentes; usuários virtuais de carga não equivalem a contas autenticadas.

| Ator conceitual | Responsabilidade |
| --- | --- |
| Investidor | Consultar mercado, enviar ordens e acompanhar execução/posição/PnL. |
| Formador de mercado | Produzir ofertas, spread e profundidade simulados. |
| Analista de risco | Configurar limites/túnel e acompanhar rejeições/poder de compra. |
| Operador de back office | Conciliar negócios, razão, posição e falhas de liquidação. |
| Câmara de liquidação | Confirmar liquidação financeira/física simulada. |
| Suporte/SRE | Investigar trajetória por logs, métricas e traces. |

Esses papéis descrevem o domínio; não criam novas telas, serviços ou permissões implementadas. A experiência deve permitir observar liquidez, impacto de ordens e slippage, inclusive quando pouca quantidade nos melhores níveis move o preço.

## Escopo funcional

O investidor poderá acompanhar cotação, spread, profundidade, execuções, posição e lucro/prejuízo; enviar ordens Market, Limit, Stop e Stop Limit, com validade Dia, IOC ou FOK, e vincular Stop Loss/Take Profit em OCO. Participantes simulados fornecerão ofertas. Haverá risco prévio, reservas, execuções parciais, cancelamento de saldo, razão em partidas dobradas e liquidação D+2.

Estão fora do escopo dados reais, múltiplas bolsas e roteamento inteligente entre elas, FIX/UMDF, leilões, circuit breaker de bolsa, iceberg, corretagem, emolumentos, impostos e aluguel real de ações. Venda a descoberto será simulada com limites. O projeto roda localmente e não contrata serviços pagos.


## Fidelidade e simplificações

| Aspecto | Tratamento no simulador |
| --- | --- |
| Formação de preço | Livro por ativo, prioridade preço-tempo e preço da oferta passiva. |
| Stops | Acionados no matching engine por simplificação; não modelam um OMS separado na corretora. |
| Venda a descoberto | Simulada com limites, sem aluguel real de ações. |
| Liquidação | D+2 simulado, sem câmara ou transferência financeira externa real. |
| Universo e sessões | Cinco ativos fictícios; sem múltiplas bolsas, leilões ou circuit breaker de bolsa. |
| Custos e protocolos | Sem corretagem, emolumentos, impostos, FIX ou UMDF. |

As substituições frente à proposta de origem estão no [índice de ADRs](../adrs/README.md). Calendário, bootstrap de preços e regras ainda abertas permanecem nas tarefas pertinentes.

## Requisitos funcionais

| ID | Requisito e resultado esperado |
| --- | --- |
| RF01 | Listar ativos e exibir último preço, variação, bid/ask, spread, volume, cinco níveis por lado e profundidade agregada. |
| RF02 | Enviar compras/vendas Market, Limit, Stop e Stop Limit, validades Dia/IOC/FOK e proteção Stop Loss/Take Profit em OCO. |
| RF03 | Rejeitar lote/tick inválidos, Stop Limit sem limite e preços fora do túnel de ±10% do último preço; a referência antes do primeiro negócio e com cotação defasada será definida na RF03. |
| RF04 | Verificar poder de compra, posição, exposição vendida e quantidade máxima; reservar recursos antes do matching. |
| RF05 | Aplicar preço-tempo, preço da oferta passiva, parciais/totais, gatilhos de stops e cancelamento do saldo OCO. |
| RF06 | Atualizar último preço, bid/ask e VWAP do dia por negócio; refletir também mudanças de ofertas sem execução. |
| RF07 | Calcular posição comprada/vendida, preço médio e resultados realizados/não realizados. |
| RF08 | Registrar negócios, razão em partidas dobradas e histórico; liquidar caixa e custódia em D+2 simulado. |
| RF09 | Exibir estados em tempo real e a linha do tempo da ordem pelos quatro serviços. |
| RF10 | Cancelar saldo elegível de Limit ou stop não acionado e liberar a reserva correta. |

Os cinco ativos, calendários, estados detalhados, escalas, arredondamento, reservas por tipo e casos de corrida serão definidos antes das tarefas que os implementam. O roadmap registra essas dependências; não são funcionalidades presumidas.


## Requisitos não funcionais

| ID | Garantia e critério de aceite |
| --- | --- |
| RNF01 | Precisão: nenhum `double` em dinheiro, preço ou quantidade; objetos de valor, escalas e verificação automatizada. |
| RNF02 | Determinismo: mesma sequência produz mesmos negócios; replay pelo Kafka. |
| RNF03 | Ordenação: escritor único e processamento sequencial por ativo. |
| RNF04 | Latência local: p99 boleta → negócio < 50 ms e matching < 1 ms/ordem, sob carga e ambiente documentados. |
| RNF05 | Reservas: nenhuma ordem admitida ao matching sem reserva; falhas tratadas sem perder recursos. |
| RNF06 | Idempotência: reentregas não duplicam negócios, lançamentos ou posições. |
| RNF07 | Observabilidade: trace por ordem, logs correlacionados, métricas e quatro dashboards de negócio. |
| RNF08 | Execução local reproduzível em Docker Compose, sem infraestrutura paga. |

As metas não estão comprovadas pela simples inicialização dos containers. A P02 valida a fundação técnica; a V01 mede o sistema financeiro integrado. A implementação e as limitações operacionais estão no HLD.


## Regras de negócio e lacunas

Prioridade preço-tempo e preço passivo, conservação de quantidade, reserva antes do matching, efeitos idempotentes, posição long/short, partidas dobradas e D+2 fazem parte do resultado requerido. O [roadmap](../roadmap.md) detalha os aceites de cada RF e as decisões que precedem implementação.

Ainda pendentes: cinco ativos e parâmetros; escalas/ticks/lotes/arredondamento; calendário e feriados; estados e combinações de ordens; reserva por tipo; corridas de cancelamento e timeout; regras de virada de posição. Não preencher esses pontos com o comportamento do projeto doador.

## Critérios de aceite e métricas de sucesso

Cada RF deve passar pelos cenários e casos negativos definidos na tarefa correspondente, via APIs e planilhas quando elegíveis pelo ADR-004. Exemplos existentes: RF04 cobre duas ordens concorrentes sem reserva duplicada; RF07 cobre aumento, redução, zeragem e virada; RF10 impede liberação dupla ou execução desfeita. Cenários ainda não executados não são evidência de aceite.

A V01 verifica RNFs com ambiente, amostras e carga documentados. Metas existentes: p99 boleta→negócio <50 ms e matching <1 ms/ordem, precisão e replay exatos; nenhuma medição financeira foi comprovada na fundação. Preservar a distinção entre espera por contraparte e tempo de processamento. Não há meta de disponibilidade nem cobertura percentual aprovada.

## Dependências, riscos e exclusões operacionais

Execução local e ausência de custos são restrições já definidas. Fluxos completos dependem das RFs, do runner P03 e das medições P06/V01; a ausência temporária de participantes pode atrasar uma ordem e deve ser tratada sem conclusão fictícia. Orçamento limitado de CPU/RAM pode impedir execução de carga segura; fórmulas e regras não definidas podem bloquear aceite financeiro.

O baseline técnico e a API de carga são habilitadores de estudo, não comprovação de capacidade de negociação. Não usar dados reais nem contratar infraestrutura. Detalhes de componentes e implantação ficam no [HLD-001](../hlds/HLD-001-arquitetura-prospero.md); decisões e trade-offs nos [ADRs](../adrs/README.md).
