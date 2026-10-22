# ADR-009 — Projeção de mercado e autenticação local do front office (RF01)

- Data: 2026-10-17.
- Estado: **aceita e implementada** na RF01 (decisões do usuário em 2026-10-17).
- Origem: lacunas bloqueantes do refinamento da [RF01](../roadmap.md#rf01--consulta-de-ativos-e-livro): projeção, ausência, indicadores e autorização.
- Decisor: usuário; detalhes técnicos derivados pela implementação e revisados por agentes independentes.

## Contexto

O [HLD-002](../hlds/HLD-002-front-office.md) previa Redis como projeção futura de cotações. O Redis atual usa `allkeys-lru` com 48 MiB: AOF não impede evicção, então um snapshot poderia sumir por popularidade. A RF01 precisa de consulta coerente, estados de ausência distintos e preparação sintética isolada por API, sem matching no FO.

## Decisões

| Tema | Decisão aceita |
| --- | --- |
| Ativos | ALFA3/BETA3/GAMA3/DELTA3/OMEGA3; referência BRL 10/20/30/40/50; tick 0,01; lote 1; quantidades inteiras (migração Flyway V2). Desde a RF03 ([ADR-011](ADR-011-validacao-preco-ordens.md), V4), OMEGA3 usa lote 100 e tick 0,05. |
| Indicadores | Último = último negócio da sessão. Variação absoluta (último − referência) **e** percentual (÷ referência × 100, duas casas HALF_EVEN). Volume em quantidade **e** financeiro (Σ preço × quantidade). Sem negócio: último, variação e volumes `null`, nunca zero. |
| Projeção | PostgreSQL próprio do FO, tabela `snapshot_mercado` com uma linha JSONB por namespace/ticker. Upsert atômico incrementa a revisão; leitura nunca combina revisões. Redis fica para cache futuro medido. |
| Ausência | `SEM_DADOS` (sem snapshot; lados e metadados do livro `null`) ≠ `VAZIO` ≠ `SOMENTE_COMPRA`/`SOMENTE_VENDA` ≠ `COMPLETO`. `defasado` quando a sessão é anterior ao dia atual de São Paulo ou o snapshot excede `prospero.mercado.idade-maxima` (24 h). A resposta do livro traz revisão, sessão e origem próprias, para a tela não combinar revisões. |
| Livro | Agregação por preço; compras decrescentes, vendas crescentes; cinco níveis exibidos, acumulado e totais de todos os níveis. Livro cruzado/travado recusado (422). |
| Autorização | **Todas** as rotas RF01 exigem JWT HS256 (`iss=prospero-local`, `aud=prospero-front-office`, claim `groups`). `mercado:leitura` para consulta, `mercado:preparacao` para PUT/DELETE de namespace. `/api/v1/sistema` continua público. |
| Credencial do navegador | O Angular não recebe token: o Nginx (e o proxy do `ng serve`) injeta o token só de leitura em `GET /api/v1/ativos*` e responde 403 para preparação. |
| Entrada | JSON estrito: campos desconhecidos, coerções (1.5 → 1, "100" → número, 10.5 → texto) e JSON malformado resultam em 400 `JSON_INVALIDO`. Problem Details com `codigo`, `instance` (caminho seguro) e `correlacao` (X-Request-ID válido ou UUID gerado), inclusive 401 (com `WWW-Authenticate: Bearer`), 403 e 415; autenticação lazy (`quarkus.http.auth.proactive=false`). Preço com gramática inválida é 422 (`PRECO_INVALIDO`), pois o tipo JSON é texto válido. |
| Preparação | Somente com `FO_PREPARACAO_HABILITADA=true` (Compose local); namespace UUID, até 50 namespaces, 100 ofertas por lado e 200 negócios; limpeza idempotente por namespace. |

## Alternativas e trade-offs

- Redis (HLD original): menor latência, mas evicção LRU e AOF tornariam ausência indistinguível de perda; exigiria repreparo e outra política de memória.
- Memória do processo: mais simples, porém perde tudo ao reiniciar.
- Consulta aberta: menos configuração, recusada pelo usuário em favor de "tudo com token".
- Token no Angular: exporia credencial no bundle/armazenamento do navegador; o proxy mantém a credencial no servidor ao custo de qualquer cliente da origem `localhost:4200` ler os dados fictícios.

## Consequências

- `FO_JWT_CHAVE` (≥32 bytes) assina tokens em `scripts/preparar-ambiente.py`/`scripts/mercado.py`; o FO recebe a mesma chave como JWK `oct` base64url (`FO_JWT_JWK`), pois o SmallRye interpreta texto base64url como JWK codificado. Rotação: remover as três chaves do `.env`, regenerar e recriar FO/frontend.
- Testes REST usam repositório em memória; o adaptador JPA é coberto pelo workbook XLSX e pelo E2E contra o PostgreSQL real.
- O limite de namespaces é verificado antes do upsert, sem lock: concorrência pode excedê-lo por poucos namespaces; o namespace principal conta no limite.
- Com a preparação desabilitada, um corpo inválido ainda retorna 400 antes do 404, porque a validação de Bean Validation precede o método; aceitável, pois `/q/openapi` já publica as rotas.
- `FO_TOKEN_LEITURA` vale 365 dias e só é revogado trocando a chave; ao expirar, remover a linha do `.env` e rodar `preparar-ambiente.py`. Ele fica visível em `docker inspect` e no `/tmp` do container Nginx, aceitável para ambiente local de dados fictícios.
- Em `quarkus dev` não há chave configurada e o Quarkus gera um par RSA: tokens HS256 locais são recusados. Use o Compose para testar autenticação.
- Dívida: o adaptador JPA não roda no `mvnw verify` (datasource inativo em teste); upsert, JSONB e consultas são cobertos pelo workbook XLSX e E2E no gate de testes. Testcontainers/Dev Services podem ser adotados quando o runner suportar Docker no build.
- A RF06a implementa a projeção real em validação conforme proposta [ADR-014](ADR-014-cotacao-real-fo.md); o namespace principal passa a ser real e UUIDs não nulos preservam os snapshots sintéticos. O snapshot sintético não alimenta risco, túnel nem matching.

## Revisão

Revisar ao implementar a RF06 (origem de eventos), ao introduzir usuários/contas reais (substituir token local por identidade) ou se medições justificarem cache Redis.
