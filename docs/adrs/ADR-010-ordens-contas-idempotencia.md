# ADR-010 — Ordens, contas sintéticas e idempotência no front office (RF02)

- Data: 2026-10-17.
- Estado: **aceita**; API e persistência implementadas na RF02a, boleta e token de escrita no proxy na RF02c.
- Origem: refinamento da [RF02](../roadmap.md#rf02--envio-e-acompanhamento-inicial-de-ordens) com cinco pareceres medium (arquitetura, QA, segurança, operação, viabilidade), todos **SEGUIR COM RESSALVAS**.
- Decisor: usuário (combinações, contas, token de escrita, limites); detalhes técnicos consolidados dos pareceres.

## Decisões

| Tema | Decisão |
| --- | --- |
| Combinações | Market só IOC/FOK; Limit DIA/IOC/FOK; Stop e Stop Limit só DIA (também `CHECK` no banco). Limit exige limite; Stop exige gatilho; Stop Limit exige ambos; preço que não se aplica ao tipo é 422. A exigência estrutural do limite do Stop Limit fica na RF02; tick, lote e túnel continuam na RF03. |
| OCO | `POST /api/v1/ordens-oco`: Take Profit (Limit) + Stop Loss (Stop/Stop Limit), mesmo ativo, lado e quantidade, só DIA, gravados atomicamente com `grupoOco`. Venda exige alvo acima do gatilho; compra (cobertura) abaixo. Posição existente é verificada na RF04. |
| Estado | Somente `RECEBIDA` (registro, sem validação de preço, reserva ou execução). Enum cresce nas RF03–RF05; nenhum estado futuro é exposto. Rejeição estrutural é 4xx síncrono e não é persistida. |
| Sessão Dia | Data civil de America/Sao_Paulo no recebimento (`dataSessao`), `Clock` injetável, sem calendário de feriados nem job de expiração (expiração em livro na RF05). |
| Contas | `CONTA-001` (tela), `CONTA-002` e `CONTA-003` (testes) fixas por migração V3. A conta vem só do claim `sub`; corpo com `conta` é 400. `sub` inexistente é 403 `CONTA_DESCONHECIDA`. |
| Escopos | `ordens:enviar` (POST) e `ordens:leitura` (GET); tokens de mercado não enviam ordens. |
| Contrato | `202 Accepted` com `Location` relativo (`/api/v1/ordens/{id}`, `/api/v1/ordens-oco/{grupo}`) e corpo com `id`/`estado`; `GET` por ID e listagem paginada por cursor opaco (1–100, padrão 50). Ordem de outra conta, inexistente ou com ID malformado: 404 indistinguível. |
| Idempotência | Cabeçalho obrigatório `X-Idempotency-Key` (8–64 `[A-Za-z0-9_-]`; prefixo `x-` exigido pelo runner XLSX). Unicidade `(conta, chave)` com SHA-256 do pedido normalizado na mesma transação das ordens; mesmo pedido devolve a mesma resposta (`X-Idempotencia-Repetida: true`), pedido divergente é 409 `IDEMPOTENCIA_CONFLITO`. Chave válida por 24 h, expurgo em lotes de 100 que nunca remove ordens. |
| Limites | Até 1000 ordens por conta e sessão, garantido por upsert condicional (429 `LIMITE_ORDENS`, desfazendo a reserva da chave); corpo até 64 KiB; quantidade até 1 000 000; preço decimal textual com até duas casas e até 1 000 000,00. |
| Mensageria | RF02 só persiste. Outbox, envelope e tópicos entram na RF04 (primeiro consumidor real); a fronteira transacional do registro é o ponto onde a outbox será acrescentada. Divergência temporária com o fluxo futuro do HLD-002. |
| Token de escrita (RF02c) | Aceito com proteções: o proxy injeta token da CONTA-001 só em `POST/GET /api/v1/ordens*`, recusa `Origin`/`Host` fora de `localhost:4200`/`127.0.0.1:4200`, aplica rate limit e usa validade curta regenerada por `preparar-ambiente.py`. Qualquer processo local pode enviar ordens fictícias pela conta demo; aceitável apenas no ambiente local. |

## Consequências

- Componentes REST comuns (`Problemas`, `ProblemaDTO`, `JacksonEstrito`, `RegraNegocioException` com categoria) migraram para `br.prospero.fo.comum`; ArchUnit proíbe dependência entre `mercado` e `ordens`.
- Métrica `prospero_ordens_recebidas_total{tipo,validade,resultado}` com labels enumeradas; logs com ID e conta, sem corpo, token ou chave.
- O adaptador JPA continua fora do `mvnw verify` (dívida do ADR-009); idempotência concorrente, OCO atômico e `CHECK`s são verificados pelo workbook `ordens.xlsx` e por envio concorrente contra o PostgreSQL real.

## Revisão

Revisar na RF03 (estados de rejeição), RF04 (outbox/SAGA e verificação de posição do OCO), RF05 (expiração e parciais) e ao adotar identidade real.
