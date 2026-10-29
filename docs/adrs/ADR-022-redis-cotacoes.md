# ADR-022 — Redis como cópia AP das cotações; PostgreSQL continua CP para a base do túnel

- Data: 2026-10-28.
- Estado: **proposta técnica implementada na V02-5**, sem aceite novo atribuído ao usuário.
- Origem: especificação de origem (Redis para cotações, disponibilidade acima de consistência) e item 5 do pedido de 2026-10-28. Complementa o [ADR-009](ADR-009-projecao-mercado-fo.md), o [ADR-011](ADR-011-validacao-preco-ordens.md) e o [ADR-014](ADR-014-cotacao-real-fo.md).
- Andamento: [roadmap](../roadmap.md#v02--ajustes-pós-validação).

## Contexto

O Redis subia no Compose sem que nenhum serviço o usasse. Cotações e livro de leitura vêm da projeção `cotacao_*` no PostgreSQL do FO (ADR-014). Essa projeção também alimenta a **base do túnel** de preço da boleta (ADR-011), onde uma base atrasada aceitaria ou rejeitaria ordem pelo motivo errado.

## Decisão

1. **Duas leituras com garantias diferentes.** A base do túnel continua lendo a projeção no PostgreSQL (CP: consistente com o que o FO já aplicou). As rotas de consulta `GET /api/v1/ativos` e `GET /api/v1/ativos/{ticker}/livro` leem primeiro o Redis (AP): um preço alguns milissegundos atrás é aceitável, e a rota deve responder mesmo com o PostgreSQL lento.
2. **Escrita.** Depois que a transação da projeção confirma um evento que mudou o mercado, o `AplicarCotacaoActor` lê o livro do PostgreSQL e grava no hash `prospero:cotacao:<ticker>`: JSON com último preço, volumes, VWAP (derivado), sessão, revisão e cinco níveis por lado, com decimais em texto. Um script Lua grava só se a versão `(sessão, revisão)` for maior que a guardada, comparando um texto de largura fixa (8 dígitos do dia + 19 da revisão). Evento repetido, fora de ordem ou reprocessado não regride o snapshot. A chave expira em 1 h e é renovada a cada escrita.
3. **Leitura.** Se o Redis não tiver a chave ou falhar, a rota lê o PostgreSQL e repõe o snapshot pelo mesmo compare-and-set. Ativo sem cotação também entra no cache, como marcador "ausente" com versão zero, que qualquer snapshot real substitui. A resposta traz `X-Origem-Dados: REDIS|POSTGRES` e `X-Idade-Dados-Ms` (tempo desde o último evento aplicado ao ativo). O catálogo estático de ativos fica em memória por 1 min, e a cópia anterior segue servindo se o banco falhar.
4. **Nunca indisponível por causa do Redis.** O cliente usa timeout de 200 ms; depois de uma falha, o adaptador pula o Redis por 5 s, e as leituras nessa janela vão direto ao PostgreSQL sem pagar timeout. Como gravações podem ter se perdido durante a falha, o cache fica sujo: a primeira operação depois da volta apaga todos os snapshots (`KEYS prospero:cotacao:*`, cinco ativos) e a leitura os repõe do PostgreSQL. Um Redis que volte com a revisão antiga nunca a serve como atual. O health do Redis está desligado (`quarkus.redis.health.enabled=false`): Redis fora não derruba a prontidão do FO. Falha de gravação nunca volta ao consumidor Kafka.
5. **Compose.** Senha `REDIS_PASSWORD` gerada por `preparar-ambiente.py` no `.env` (nada versionado). Ela entra no `redis-server` pela entrada padrão, fora da linha de comando do processo; o healthcheck usa `REDISCLI_AUTH`. Limite de 48 MB com `allkeys-lru` e container com 96 MB.
6. **Métricas.** `prospero_cotacao_leituras_total{origem}`, `prospero_cotacao_redis_gravacoes_total{resultado}` e `prospero_cotacao_redis_falhas_total{operacao}`; dois painéis novos no dashboard de matching/mercado (taxa de acerto e leituras que caíram para o PostgreSQL).
7. **Pub/sub para o WebSocket: não feito.** O FO roda numa instância só, e o WebSocket já é alimentado pela trajetória durável (ADR-017). Usar pub/sub agora acoplaria o tempo real ao Redis sem ganho medido. Fica como evolução quando houver mais de uma instância do FO.

## Alternativas

| Alternativa | Por que não |
| --- | --- |
| Remover o Redis do Compose | A medição mostrou ganho: a leitura sobrevive ao PostgreSQL do FO fora e o p50 cai. Remover voltaria a amarrar a consulta de cotações ao banco. |
| Base do túnel também pelo Redis | Trocaria consistência por velocidade justamente onde a regra de preço decide aceitação (ADR-011). |
| Invalidar a chave em vez de gravar o snapshot | Cada leitura depois de um evento iria ao banco; o snapshot versionado evita isso e não regride. |
| Cache em memória do FO | Não é compartilhado entre instâncias nem sobrevive a reinício; o Redis já estava no ambiente. |

## Consequências

- Mais uma dependência no FO (`quarkus-redis-client`, versão do BOM do Quarkus 3.40.1).
- Se o volume do PostgreSQL do FO for recriado e o Redis mantido, revisões novas podem ficar abaixo das guardadas até a chave expirar (1 h). O caso é raro no laboratório e está documentado; apagar `prospero:cotacao:*` resolve na hora.
- Leitura pelo Redis não passa pela verificação de "projeção em dia" do PostgreSQL. O campo `defasado` continua refletindo a situação do consumidor e a sessão.

## Medição

2026-10-29, projeto Compose `prospero-v02` com a imagem desta branch, máquina WSL2 (12 CPUs, 8 GB), FO limitado a 1 CPU e 384 MiB, outros projetos do host ativos. Script versionado `scripts/medir-leitura-cotacao.py`, 2000 leituras sequenciais por rota após 1000 de aquecimento; saídas brutas em [`docs/medicoes/`](../medicoes/).

| Rota | Redis desligado p50 / p95 / p99 | Redis ligado p50 / p95 / p99 |
| --- | --- | --- |
| `GET /api/v1/ativos` (5 ativos) | 3,57 / 6,02 / 44,78 ms | 2,20 / 4,60 / 49,72 ms |
| `GET /api/v1/ativos/ALFA3/livro` | 1,34 / 1,90 / 10,80 ms | 1,04 / 1,35 / 18,08 ms |

O p50 cai cerca de 38% na listagem e 22% no livro; o p99 é dominado por pausas do processo com 1 CPU e não melhora, em nenhum dos sentidos de forma confiável. O ganho que justifica manter o Redis é a **disponibilidade**: com o `postgres-front-office` parado, as três rotas responderam 500 com o Redis desligado e 200 (4–9 ms) com ele ligado ([evidência](../medicoes/2026-10-29-disponibilidade-cotacao.json)). Uma medição só, num host compartilhado: indicativa, sem repetição entre execuções.

## Validação

`SerializadorCotacaoTest` (escala exata, ordem da versão), `CotacaoRedisForaTest` (Redis habilitado e parado: 200 com `X-Origem-Dados: POSTGRES`), `CotacaoResourceTest` (origem no cabeçalho) e `CacheCotacaoRedisIT` contra Redis real com senha (leitura, compare-and-set com repetido/fora de ordem/sessão nova, TTL, API servindo do Redis).
