# Reaproveitamento do MinervaFinancas

## Fonte e critério

Análise do [MinervaFinancas](https://github.com/Cris042/MinervaFinancas/tree/34fc98a6ac47f2ffc2bf23f055a3fd9ac15c7530), commit `34fc98a6ac47f2ffc2bf23f055a3fd9ac15c7530`. O doador usa Java 25/Spring Boot/JDBC/SQLite e React; o Próspero usa Quarkus/JPA/PostgreSQL e Angular. A inspeção não encontrou licença explícita. Esta entrega implementa conceitos e casos de teste de forma autoral, sem copiar ou redistribuir arquivos do doador; atribuição não substitui licença.

## Aproveitado na P05

Os tipos `comum/dominio/Dinheiro`, `Quantidade`, `PrecoUnitario`, o helper `Requisicoes.escalado` e `ValoresMonetariosTeste` mostram representação por inteiro escalado e conversão exata. O MO recebe um núcleo técnico independente de HTTP: ValorEscalado, RegraDeSinal e ConversaoDecimalExata, com validação também no construtor e limites antes da expansão decimal. Não existem dependências novas nem biblioteca compartilhada.

As escalas 2/2/8 da referência não foram adotadas como regra financeira. Nossos tipos de dinheiro, preço e quantidade dependem da RF03. Aceitar zeros finais sem perda numérica é explícito; frações não representáveis e overflow falham, sem arredondamento.

## Não transportado

- `CalculoPosicao`: não cobre short/virada e seu resultado acumulado não equivale ao PnL realizado exigido na RF07. Comprar duas unidades por 100 e vender uma por 70 resulta em ganho realizado 20 com custo médio 50; subtrair todas as compras das vendas daria −30.
- Conta corrente e movimentações: saldo de caixa não equivale à reserva intradiária por ordem. A transação monolítica entre movimento/caixa não atravessa nossos bancos isolados.
- Concorrência SQLite: serialização por transação IMMEDIATE não prova atomicidade de check-then-insert em PostgreSQL. Reaproveitar cenários conceituais, não garantias do driver.
- Calendário apenas por dias de semana não resolve feriados e D+2; decidir na RF08.

A referência não fornece SAGA, outbox, idempotência ou reservas adequadas ao nosso fluxo. Esses itens permanecem no roadmap; o reaproveitamento não conclui RF04 nem RF07.

## Verificação e trade-offs

A P05 exige testes internos de precisão, sinal, extremos long, igualdade representacional e limites técnicos, além do verify isolado do MO e revisão/segurança independentes. Sem endpoint novo, planilha dispensada pelo ADR-004. Os acessores do record imutável são uma exceção de representação técnica a Object Calisthenics, sem estado mutável exposto. A limitação de escala/precisão é técnica e não define a precisão financeira do produto.
