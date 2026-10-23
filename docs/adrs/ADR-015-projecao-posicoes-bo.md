# ADR-015 — Projeção de posições no back office

- Data: 2026-10-23.
- Estado: **proposta**; sem implementação ou aceite financeiro.
- Origem: refinamento inicial da RF07, após merge autorizado do PR #38.
- Decisor: usuário; decisões de negócio pendentes no [roadmap](../roadmap.md#rf07--posições-e-resultado-financeiro).
- Relação: complementa ADR-001/002/003/004 e ADR-013; não altera reservas, admissão ou liquidação.

## Contexto

O [PRD RF07](../prds/PRD-001-simulador-prospero.md) exige posições compradas/vendidas, preço médio e resultados realizados/não realizados. O BO possui apenas fundação. `NEGOCIO` em `matching.eventos.v1` já identifica ambas as contas, preço, quantidade, ativo, sessão e negócio; não é necessário consultar bancos de outros serviços para obter participantes.

A custódia sintética do MO não transporta custo histórico de abertura. Começar uma projeção em zero pode produzir quantidade líquida vendida depois de uma venda coberta pela custódia do MO. O significado da posição precisa de decisão de produto antes da implementação, conforme lacunas do roadmap. A autonegociação permitida pelo ADR-013 também exige política explícita para não realizar PnL arbitrário pela ordem de aplicação dos lados.

## Alternativas

| Alternativa | Benefício | Limitação |
| --- | --- | --- |
| Manter só a fundação | Preserva o estado atual enquanto as regras estão abertas | Não entrega RF07 |
| Projeção BO a partir dos negócios desde a origem do log | Usa contratos existentes, permite replay e mantém autonomia | Não representa custódia patrimonial inicial; precisa de rótulo e aceite explícitos |
| Incluir posição/custo de abertura por evento próprio | Permite visão patrimonial com custo inicial conhecido | Exige contrato e regras de preparação/reconciliação ainda inexistentes; não inventar custo |
| Calcular no MO ou ler seu banco | Reaproveitaria a custódia atual | Viola a responsabilidade BO e, no acesso ao banco, o ADR-001 |

## Desenho proposto

Consumidor BO com grupo próprio lê `matching.eventos.v1` sequencialmente por partição, desde a origem disponível. Valida envelope limitado, chave/ativo, versões, tipos e participantes; contrato desconhecido ou incoerente interrompe o processamento com diagnóstico limitado, sem descarte silencioso. O grupo novo não prova completude se o início do histórico não estiver disponível.

Uma transação local confirma identificação/assinatura do evento e negócio, os efeitos nas duas posições, último preço e checkpoint da partição. Confirmar Kafka somente depois do commit. Duplicata idêntica é inócua; mesma identidade com conteúdo divergente bloqueia. Checkpoint, identidade do tópico e ordenação devem impedir reaplicação fora de ordem, inclusive após recriação do tópico. Eventos conhecidos sem efeito financeiro também avançam o progresso. Aquisição de posições usa ordem determinística.

O domínio puro deve cobrir aumento, redução, zeragem e virada nos dois sentidos. A proposta financeira completa e exemplos estão no roadmap; não são decisões aceitas. Para evitar arredondamento cumulativo, avaliar representação racional exata com orçamento explícito de tamanho/custo e falha observável ao excedê-lo. Não escolher limite arbitrário nem arredondar silenciosamente; essa avaliação precede o contrato de persistência.

`GET /api/v1/posicoes` no BO determina a conta pelo JWT validado, com audiência BO e escopo de leitura próprios. Resposta limitada aos cinco ativos, valores financeiros textuais e `Cache-Control: no-store`. Estado de reconstrução, cotação ausente/defasada e posição confirmada zero são distintos. O proxy injeta credencial somente no servidor, por rota/método exatos, descartando Authorization recebido; a rota genérica não pode permitir acesso alternativo. A tela identifica a conta e o significado financeiro aprovado, sem calcular PnL em `Number`.

## Consequências e limites

- Permite reconstrução e testes sem banco alheio ou biblioteca compartilhada entre contextos.
- Acrescenta persistência mínima para idempotência da RF07; razão em partidas dobradas, histórico contábil e D+2 continuam na RF08. Não credita caixa/custódia no MO.
- Cotação pode ser derivada do mesmo negócio; ofertas sem execução não alteram o não realizado. Defasagem e fechamento de sessão precisam de contrato observável.
- Uma sessão por log e retenção integral do laboratório continuam valendo. Replay incompleto não autoriza mostrar zero como saldo atual.
- Kafka PLAINTEXT permite forjar negócio coerente e alterar posições/PnL; origem textual e hash não autenticam produtor. Não há adoção de HMAC/ACL/TLS nesta proposta.
- Nenhuma migração foi criada. A expansão futura deve ser aditiva e documentar recuperação por imagem anterior ou migração corretiva, preservando dados; reconstrução destrutiva exige autorização própria.

## Validação e condição de revisão

Antes do código dependente: resolver lacunas de produto, orçamento numérico, checkpoint/identidade do log e completar o refinamento com a independência exigida. Depois: testes de domínio/propriedades, JWT e contrato REST, PostgreSQL real para atomicidade/deduplicação/conflito/replay, consumidor controlado, workbook via APIs e E2E desktop/mobile com imagens. Nenhuma dessas provas RF07 foi executada nesta proposta.

Rever ao incluir custo de abertura, alterar política de short/autonegociação, abrir múltiplas sessões, mudar retenção/partições ou integrar a contabilidade RF08. Desenho do contexto: [HLD-005](../hlds/HLD-005-back-office.md).
