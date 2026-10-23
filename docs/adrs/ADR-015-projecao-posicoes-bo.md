# ADR-015 — Projeção de posições no back office

- Data: 2026-10-23.
- Estado: **aceita em 2026-10-23**, em implementação; proposta originalmente registrada no PR #41.
- Origem: refinamento inicial da RF07, após merge autorizado do PR #38.
- Decisor: usuário, confirmação explícita “conformo sim, pode seguir” após apresentação das regras; andamento no [roadmap](../roadmap.md#rf07--posições-e-resultado-financeiro).
- Relação: complementa ADR-001/002/003/004 e ADR-013; não altera reservas, admissão ou liquidação.

## Contexto

O [PRD RF07](../prds/PRD-001-simulador-prospero.md) exige posições compradas/vendidas, preço médio e resultados realizados/não realizados. Na proposta original o BO possuía apenas fundação; a implementação RF07 está em validação. `NEGOCIO` em `matching.eventos.v1` já identifica ambas as contas, preço, quantidade, ativo, sessão e negócio; não é necessário consultar bancos de outros serviços para obter participantes.

A custódia sintética do MO não transporta custo histórico de abertura. Começar uma projeção em zero pode produzir quantidade líquida vendida depois de uma venda coberta pela custódia do MO. Essas lacunas foram apresentadas ao usuário e fechadas pelo aceite explícito das regras abaixo, preservando o contexto da proposta.

## Alternativas

| Alternativa | Benefício | Limitação |
| --- | --- | --- |
| Manter só a fundação | Preserva o estado atual enquanto as regras estão abertas | Não entrega RF07 |
| Projeção BO a partir dos negócios desde a origem do log | Usa contratos existentes, permite replay e mantém autonomia | Não representa custódia patrimonial inicial; precisa de rótulo e aceite explícitos |
| Incluir posição/custo de abertura por evento próprio | Permite visão patrimonial com custo inicial conhecido | Exige contrato e regras de preparação/reconciliação ainda inexistentes; não inventar custo |
| Calcular no MO ou ler seu banco | Reaproveitaria a custódia atual | Viola a responsabilidade BO e, no acesso ao banco, o ADR-001 |

## Regras confirmadas pelo usuário (2026-10-23)

Aumento no mesmo lado calcula médio ponderado exato; redução conserva o médio e realiza `(preço executado − médio) × quantidade encerrada × sinal da posição anterior`. Zeragem remove o médio e preserva realizado acumulado; virada abre o saldo ao preço do negócio. Não realizado é `(cotação − médio) × quantidade assinada`. Apresentação com quatro casas HALF_EVEN; não arredondar acumuladores.

A posição parte de zero na origem do log e representa os negócios, separada da custódia sintética sem custo histórico. Autonegociação preserva quantidade, médio e realizado da própria conta e atualiza a cotação. A restrição de venda descoberta do risco permanece. A aceitação resolve as lacunas financeiras da proposta; não representa implementação comprovada.

## Desenho aceito

Consumidor BO com grupo próprio lê `matching.eventos.v1` sequencialmente por partição, desde a origem disponível. Valida envelope limitado, chave/ativo, versões, tipos e participantes; contrato desconhecido ou incoerente interrompe o processamento com diagnóstico limitado, sem descarte silencioso. O grupo novo não prova completude se o início do histórico não estiver disponível.

Uma transação local confirma identificação/assinatura do evento e negócio, os efeitos nas duas posições, último preço e checkpoint da partição. Confirmar Kafka somente depois do commit. Duplicata idêntica é inócua; mesma identidade com conteúdo divergente bloqueia. Checkpoint, identidade do tópico e ordenação devem impedir reaplicação fora de ordem, inclusive após recriação do tópico. Eventos conhecidos sem efeito financeiro também avançam o progresso. Aquisição de posições usa ordem determinística.

O domínio puro deve cobrir aumento, redução, zeragem e virada nos dois sentidos. As regras financeiras acima e exemplos do roadmap foram confirmados pelo usuário. A implementação usa frações reduzidas por MDC, sem arredondamento cumulativo. O orçamento e sua recuperação estão descritos abaixo.

`GET /api/v1/posicoes` no BO determina a conta pelo JWT validado, com audiência BO e escopo de leitura próprios. Resposta limitada aos cinco ativos, valores financeiros textuais e `Cache-Control: no-store`. Estado de reconstrução, cotação ausente/defasada e posição confirmada zero são distintos. O proxy injeta credencial somente no servidor, por rota/método exatos, descartando Authorization recebido; a rota genérica não pode permitir acesso alternativo. A tela identifica a conta e o significado financeiro aprovado, sem calcular PnL em `Number`.

## Consequências e limites

- Permite reconstrução e testes sem banco alheio ou biblioteca compartilhada entre contextos.
- Acrescenta persistência mínima para idempotência da RF07; razão em partidas dobradas, histórico contábil e D+2 continuam na RF08. Não credita caixa/custódia no MO.
- Cotação pode ser derivada do mesmo negócio; ofertas sem execução não alteram o não realizado. Sem cotação, outra sessão ou sessão encerrada são sinalizados; lag/boot/quarentena não retornam zeros confirmados.
- Uma sessão por log e retenção integral do laboratório continuam valendo. Replay incompleto não autoriza mostrar zero como saldo atual.
- Kafka PLAINTEXT permite forjar negócio coerente e alterar posições/PnL; origem textual e hash não autenticam produtor. Não há adoção de HMAC/ACL/TLS nesta entrega.
- V2 é expansão aditiva: `bo_log`, `bo_checkpoint`, `bo_transporte`, `bo_evento`, `bo_negocio`, `bo_cotacao` e `bo_posicao`; não altera V1 nem dados de outros contextos. Em regressão, a imagem anterior da fundação preserva tabelas mas interrompe RF07. Preferir correção para frente, com cópia recuperável se houver dados. Não editar migração aplicada nem limpar tabelas parcialmente; reconstrução destrutiva exige autorização própria.

## Orçamento numérico e recuperação

Cada componente racional reduzido aceita até 131072 bits. Antes da redução, produtos/somas aceitam até 262146 bits. Numerador persistido em texto canônico tem no máximo 39458 caracteres com sinal; denominador positivo, 39457. Ampliação acima do orçamento lança `ORCAMENTO_RACIONAL`: a transação inteira reverte, a partição entra em quarentena fora da transação e a consulta fica indisponível. Se o limite for excedido apenas na marcação derivada da leitura, retornar503/no-store sem snapshot parcial ou zeros; a leitura não altera posições nem progresso. Não truncar, aproximar ou avançar o checkpoint para continuar. A correção pode ampliar um orçamento medido em nova versão, preservando o registro que falhou; não reexecutar com nova identidade.

Sondas da implementação real em JVM26, container384 MiB/1CPU e heap64 MiB, verificaram componentes de 131072 bits, produtos temporários próximos de 262143 bits, recusa do excesso e roundtrip exato no PostgreSQL16.14. Operação adversa perto de 60 mil bits continua custosa (dez ciclos de posição em aproximadamente2,84 s). Esses experimentos dimensionam a proteção e não comprovam capacidade, prazo de replay ou RNF04. Detalhes/resultados da execução ficam no PR; não adotar outra biblioteca ou aproximação por antecipação.

Bootstrap durável vincula uma única identidade de tópico às três partições, com origem zero e retenção integral. O consumidor valida também a partição correspondente à chave e revalida identidade/metadados durante a operação. A transação trava o checkpoint da partição; coordenada antiga exige hash correspondente, enquanto republicação econômica em offset novo só avança transporte. `committedKafka > PGnext`, progresso além do fim, lacuna, identidade alterada ou bootstrap parcial bloqueiam. Rebalance invalida prontidão; falha após poll rebobina o lote. Kafka só confirma depois de PostgreSQL; ack perdido resulta em reentrega idempotente.

A API retorna 503 Problem Details/no-store durante bootstrap, lag ou quarentena. Quando utilizável, o snapshot informa cotação ausente/antiga/encerrada; não realizado sem preço permanece ausente. A prontidão e as métricas distinguem projeção utilizável de dependências acessíveis. Reconstrução exige parar só o consumidor BO, preservar cópia recuperável e tratar fatos, coordenadas, posições, cotação e checkpoints como uma unidade; não executar reset automático ou apagar volumes.

## Validação e condição de revisão

O refinamento com cinco agentes e o aceite financeiro antecederam o código. Domínio, propriedades, JWT/REST, PostgreSQL real e consumidor controlado têm evidência local; workbook via APIs, E2E com imagens e os gates remotos pertencem à validação integrada do PR da implementação. A planilha usa perfil isolado antes de outras negociações DELTA3 e sequências fechadas que permitem duas repetições sem apagar histórico; não funciona sobre baseline financeiro arbitrário.

Rever ao incluir custo de abertura, alterar política de short/autonegociação, abrir múltiplas sessões, mudar retenção/partições ou integrar a contabilidade RF08. Desenho do contexto: [HLD-005](../hlds/HLD-005-back-office.md).
