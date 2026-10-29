# V01: validação integrada dos requisitos não funcionais

Data de referência: 2026-10-28. Commit de medição: `5ceb8b9` (main). Escopo deste documento: matriz de evidências RNF01–RNF08, execução local, medição do RNF04 e roteiro de demonstração. Não altera requisito, arquitetura nem código.

Estado: **concluída** com o merge deste fechamento, após o [PR #69](https://github.com/mclovin137/Prospero/pull/69) (medição dedicada) e o [PR #68](https://github.com/mclovin137/Prospero/pull/68) (P06 B3). O [roadmap](roadmap.md#v01--validação-integrada-dos-requisitos-não-funcionais) registra o estado.

## Vereditos por RNF

Veredito: **comprovado** (evidência automatizada ou medição que cobre o critério); **comprovado com limites** (evidência existe, mas cobre parte do critério); **não cumprido** (medição contra a meta falhou).

| RNF | Evidência existente (caminhos verificados) | Veredito | Lacunas |
| --- | --- | --- | --- |
| RNF01 Precisão | ArchUnit de domínio sem `double`/`Double` em [FO](../front-office/src/test/java/br/prospero/fo/ArquiteturaTest.java), [MO](../middle-office/src/test/java/br/prospero/mo/ArquiteturaTest.java), [ME](../matching-engine/src/test/java/br/prospero/me/ArquiteturaTest.java) e [BO](../back-office/src/test/java/br/prospero/bo/ArquiteturaTest.java); [DecimalExatoTest](../middle-office/src/test/java/br/prospero/mo/decimal/DecimalExatoTest.java); [PosicaoTest](../back-office/src/test/java/br/prospero/bo/posicoes/PosicaoTest.java) com preço médio e resultado exatos. | Comprovado com limites | A regra ArchUnit olha apenas campos em `..dominio..`; parâmetros, retornos e DTOs não entram nela. Escalas e arredondamento dependem dos testes de cada domínio. |
| RNF02 Determinismo | Replay com os mesmos bytes e identificadores: [EnvelopeMatchingTest](../matching-engine/src/test/java/br/prospero/me/matching/EnvelopeMatchingTest.java) (`replayProduzMesmosIdsEEnriquecePassiva`), [Rf10aHistoricoTest](../matching-engine/src/test/java/br/prospero/me/matching/Rf10aHistoricoTest.java), [OcoIntegracaoTest](../matching-engine/src/test/java/br/prospero/me/matching/OcoIntegracaoTest.java) (`replayDoParReproduzBytes`), [OrigensVivasTest](../matching-engine/src/test/java/br/prospero/me/livro/dominio/OrigensVivasTest.java). Oráculo de replay em [StopsMatchingTest](../matching-engine/src/test/java/br/prospero/me/livro/StopsMatchingTest.java). Exemplo de planilha: `frontend/runner/exemplos/matching.xlsx`. | Comprovado com limites | Não há teste de queda real do broker durante o replay. A reconstrução a partir do log Kafka está descrita no [HLD-004](hlds/HLD-004-matching-engine.md), mas a cobertura automatizada é de testes de integração e unidade. |
| RNF03 Ordenação | Escritor único por instância de `MotorMatching`, documentado no [HLD-004](hlds/HLD-004-matching-engine.md) (seções de integração e de limites). Sequência global de chegada em [EncerramentoSessaoTest](../matching-engine/src/test/java/br/prospero/me/livro/EncerramentoSessaoTest.java) (`expiraSaldosEmSequenciaGlobalDeChegada`). No BO, [CicloPosicoesTest](../back-office/src/test/java/br/prospero/bo/posicoes/CicloPosicoesTest.java) cobre três partições obrigatórias e ativo em partição diferente do hash. | Comprovado com limites | Cada instância tem um escritor; vários ativos na mesma instância não ganham paralelismo. Ativo quente limita a vazão da própria sequência (HLD-004). Não há teste de tentativa de escrita concorrente. |
| RNF04 Latência | Matching: medição dedicada de 2026-10-28 (ver seção própria abaixo). Boleta → negócio: mesma medição. Histograma com buckets de 25 a 75 ms no FO; script [medir-rnf04.py](../scripts/medir-rnf04.py) com teste em [test_medir_rnf04.py](../scripts/tests/test_medir_rnf04.py). | **Não cumprido** (boleta → negócio). Matching cumprido na medição dedicada. | Uma única execução dedicada. Saída JSON bruta não versionada. Execução hospedada da CI é indicativa e seu resultado não está registrado aqui. |
| RNF05 Reservas | [SagaRiscoTest](../front-office/src/test/java/br/prospero/fo/ordens/SagaRiscoTest.java) (`recusaSincronaNaoGravaComando`, `resultadoAvancaUmaVezSomenteNaContaDaIntencao`); [CalculoReservaTest](../middle-office/src/test/java/br/prospero/mo/risco/CalculoReservaTest.java); cancelamento com reserva liberada pelo MO em `CancelamentoCoordenacaoPostgresIT` e `CancelamentoCorridaPostgresIT` (MO); E2E [boleta.spec.ts](../frontend/e2e/boleta.spec.ts) (cancelamento da ordem aberta com saldo liberado pelo backend). ADR-012 e ADR-013. | Comprovado com limites | Não há teste de carga com falha injetada no meio da SAGA. A cobertura de falhas vem de testes de integração pontuais. |
| RNF06 Idempotência | Reentrega de crédito: [EventoCreditoTest](../back-office/src/test/java/br/prospero/bo/liquidacao/EventoCreditoTest.java); [LiquidacaoPostgresIT](../back-office/src/test/java/br/prospero/bo/liquidacao/LiquidacaoPostgresIT.java) (`repeticaoDoAvancoEDoWorkerDepoisDoCommitNaoDuplicamMesmoSemRelay`) e, no MO, [LiquidacaoPostgresIT](../middle-office/src/test/java/br/prospero/mo/liquidacao/LiquidacaoPostgresIT.java) (`expectativaIdenticaCreditaUmaVezMesmoComReentregas`). Boleta: [OrdensResourceTest](../front-office/src/test/java/br/prospero/fo/ordens/OrdensResourceTest.java) (`idempotenciaRepeteDivergeEIsolaContas`). Cancelamento: [CancelamentoManualTest](../matching-engine/src/test/java/br/prospero/me/livro/CancelamentoManualTest.java) (`repeticaoNaoProduzSegundoFatoENaoRessuscitaOrdem`). Outbox: [PublicadorOutboxPostgresIT](../middle-office/src/test/java/br/prospero/mo/mensageria/kafka/PublicadorOutboxPostgresIT.java) (`ackSeguidoDeRollbackPermiteReentregaDoMesmoPayload`). | Comprovado com limites | Os testes cobrem reentregas pontuais, não reentregas sob carga. |
| RNF07 Observabilidade | Trace e correlação: [RastreioMatchingTest](../matching-engine/src/test/java/br/prospero/me/matching/kafka/RastreioMatchingTest.java) e [RastreioPosicoesTest](../back-office/src/test/java/br/prospero/bo/posicoes/kafka/RastreioPosicoesTest.java); formato de log com `traceId` e `spanId` nos quatro `application.properties`. Dashboards provisionados: [ordens](../infra/grafana/dashboards/ordens.json), [risco](../infra/grafana/dashboards/risco.json), [matching](../infra/grafana/dashboards/matching.json) e [back-office](../infra/grafana/dashboards/back-office.json), além de [carga](../infra/grafana/dashboards/carga.json) e [fundação](../infra/grafana/dashboards/fundacao.json). Alertas: [alertas.yml](../infra/prometheus/alertas.yml) (dez regras) com testes promtool em [alertas.test.yml](../infra/prometheus/alertas.test.yml); verificador [verificar-observabilidade.py](../scripts/verificar-observabilidade.py); E2E [observabilidade.spec.ts](../frontend/e2e/observabilidade.spec.ts). Captura real dos quatro dashboards na [CI da V01a](https://github.com/mclovin137/Prospero/actions/runs/40385918304) (head `564e220`). | Comprovado com limites | Alertas são locais, sem Alertmanager nem notificação externa. Série ausente aparece como ausente, não como zero. Dashboards não substituem a conciliação por APIs. |
| RNF08 Ambiente local | [docker-compose.yml](../docker-compose.yml) (projeto `prospero`, portas em `127.0.0.1`, limites de memória e CPU); [preparar-ambiente.py](../scripts/preparar-ambiente.py) e [verificar-ambiente.py](../scripts/verificar-ambiente.py), com [test_preparar_ambiente.py](../scripts/tests/test_preparar_ambiente.py); execução em `testes.yml` por [ci-testes.sh](../scripts/ci-testes.sh). Subida local em 2026-10-28 (ver seção de execução). | Comprovado com limites | Validado somente em WSL2 com 12 CPUs e 8 GB. As portas são fixas, então dois projetos Compose não sobem ao mesmo tempo. |

## RNF04: medição e atribuição

Protocolo: [medir-rnf04.py](../scripts/medir-rnf04.py) mede a boleta até o negócio e o matching por comando, com contraparte já em repouso (CONTA-003), agressores Limit IOC de 1 unidade, CONTA-005 como única compradora medida e CONTA-002 só para aquecimento. Os percentis são **limites superiores de bucket** do histograma Prometheus, após excluir o aquecimento. O veredito só marca cumprimento quando o limite superior do p99 é estritamente menor que a meta.

Medição dedicada (2026-10-28, SHA `5ceb8b9`, máquina WSL2 com 12 CPUs e 8 GB, Docker 29.1.3, somente a stack do Próspero em projeto Compose limpo, outros projetos parados com autorização do usuário, limites de 384 MiB e 1 CPU por serviço Java, 1000 amostras e 30 de aquecimento):

| Medida | p50 | Média | p99 | Meta | Veredito |
| --- | --- | --- | --- | --- | --- |
| Matching por comando | 0,17 ms | não registrada | 0,61 ms | < 1 ms | Cumprido |
| Boleta → negócio | 35 ms | 39,5 ms | 223,7 ms | p99 < 50 ms | **Medido, não cumprido** |

Atribuição por salto (96 traces amostrados no Jaeger): o span do matching-engine concentra a cauda, com p99 de 85,6 ms, enquanto os demais saltos ficam entre 4 e 10 ms. O comando de matching em si fica abaixo de 1 ms. O excesso está no entorno do ME: consumo e publicação Kafka, outbox e persistência. O algoritmo não é o gargalo medido.

Decisão do usuário (2026-10-27): registrar o desvio como "medido, não cumprido", com atribuição por salto, sem otimizar nesta fatia. Direção de otimização futura, a decidir pelo usuário: reduzir a espera entre publicação, consumo e persistência no entorno do ME. A [ADR-018](adrs/ADR-018-publicacao-outbox-apos-commit.md) reduziu a espera de publicação da outbox: na comparação indicativa da CI, o limite superior do p99 da boleta caiu de 447,4 ms para 67,1 ms. A medição dedicada, ainda assim, registra 223,7 ms e cauda concentrada no ME.

**Atualização V02-6 (2026-10-29).** Quatro medições de 1000 amostras no protocolo acima, em projetos limpos, com os outros projetos do host ativos. A base `a0d753d` registrou p50 ≤ 39,1 ms, média de 42,4 ms e p99 ≤ 246,1 ms. Depois de `linger.ms=0` no ME, do broker Kafka com 384 MB e G1 e da publicação em lote no ME (`52464dc`), o resultado foi p50 ≤ 33,6 ms, média de 40,1 ms e p99 ≤ 246,1 ms. O span do ME caiu de 20,8 para 4,1 ms no p50, e o matching continua com p99 ≤ 0,70 ms. **RNF04 segue não cumprido** na boleta. A cauda passou do ME para paradas simultâneas de FO, MO e BO com o host em pressão de memória e IO. Tabela, atribuição e o que falta estão no [roadmap](roadmap.md#v02-6--medições).

Execuções hospedadas na CI usam 600 amostras e 30 de aquecimento, como **indicativo** (runner compartilhado de 4 CPUs e 16 GB, Docker 28.0.4). Nenhuma delas substitui a medição dedicada.

## Dependências, divergências e lacunas

Dependência declarada: a V01 depende de P06 e RF01–RF10 ([roadmap](roadmap.md#v01--validação-integrada-dos-requisitos-não-funcionais)).

- **P06 concluída:** B1 (PR #51), B2 (PR #61) e B3 ([PR #68](https://github.com/mclovin137/Prospero/pull/68), merge `99fc45d`) integradas; a dependência da V01 está cumprida.
- **[DOC-MISMATCH]** [HLD-004](hlds/HLD-004-matching-engine.md), seção do RF10c2: afirma que "ninguém produz o comando ainda" (`CANCELAR_ORDEM`). O MO já produz o comando em [EmissorCancelamento.java](../middle-office/src/main/java/br/prospero/mo/cancelamento/actor/EmissorCancelamento.java).
- **[DOC-MISMATCH]** Amostras da CI: o [roadmap](roadmap.md#v01-1-e-v01-2--prontidão-por-lag-e-medição-rnf04) registra a decisão de "pelo menos 1000 amostras" na CI; o [HLD-004](hlds/HLD-004-matching-engine.md) e o [ci-testes.sh](../scripts/ci-testes.sh) usam 600. A medição dedicada, com 1000, é a que vale para o veredito.
- **[DOC-MISMATCH]** [README](../README.md) descrevia os quatro dashboards como "em validação"; a V01a já foi integrada pelo PR #52.

Lacunas abertas:

1. ~~Saída JSON bruta da medição dedicada não está versionada.~~ **Fechada pela V02-6 (2026-10-29):** as quatro medições de 1000 amostras (base e três mudanças) estão versionadas em [docs/medicoes](medicoes/), com a atribuição por salto; a medição de `5ceb8b9` continua só nesta página.
2. Resultado e link da execução indicativa da CI não foram registrados no roadmap. Há run `success` de `main` em `7905dfa` ([40690304557](https://github.com/mclovin137/Prospero/actions/runs/40690304557)) e em `5ceb8b9` ([40765213108](https://github.com/mclovin137/Prospero/actions/runs/40765213108)), mas o veredito indicativo não foi extraído dos logs.
3. Uma única execução dedicada. Não há repetição nem distribuição entre execuções. A V02-6 acrescentou quatro execuções em projetos limpos, mas com os demais projetos do host ativos; elas não são repetição da condição dedicada da V01.
4. ~~Os volumes do projeto Compose antigo `prospero` bloqueiam o replay do matching-engine (`IllegalArgumentException`) com o código atual.~~ **Reclassificada pela V02-1 (2026-10-28):** não é regressão. O log antigo mistura admissões de 2026-10-24 e 2026-10-25 na partição 0 (checkpoint no offset 6), o que viola a regra de uma sessão por log do ADR-013. Agora o replay para com diagnóstico explícito, teste de regressão e procedimento documentado no [HLD-004](hlds/HLD-004-matching-engine.md#replay-bloqueado-por-sessão-divergente). Abrir a sessão seguinte no mesmo log continua como evolução pendente de decisão do usuário. Os volumes seguem preservados.
5. Custo de CPU e de banco de dados em repouso não tem fonte medida (ver a análise do [ADR-018](adrs/ADR-018-publicacao-outbox-apos-commit.md)).
6. GIF do fluxo não foi gravado. O roteiro abaixo serve de base.

## Execução local

Pré-requisitos: Docker Engine com Compose v2 e Python 3. Ambiente de referência: WSL2 com 12 CPUs e 8 GB. Os limites por serviço estão em [docker-compose.yml](../docker-compose.yml): 384 MiB e 1 CPU para cada serviço Java (FO, MO, ME e BO), 640 MiB para o Kafka, e 128 MiB ou menos para os bancos.

Subida e verificação (comandos do [README](../README.md#subir-o-ambiente)):

```bash
python3 scripts/preparar-ambiente.py
docker compose config --quiet
COMPOSE_PARALLEL_LIMIT=1 docker compose build
docker compose up -d --wait --wait-timeout 240
python3 scripts/verificar-ambiente.py
```

Medição do RNF04 (contexto dedicado, exige 1000 amostras e no máximo 1000 ordens por conta):

```bash
python3 scripts/medir-rnf04.py --origem-jaeger http://127.0.0.1:16687 \
  --comprador CONTA-005 --comprador-aquecimento CONTA-002 \
  --contexto dedicado --amostras 1000 --aquecimento 30 > <saida-fora-do-Git>
```

O script redefine as carteiras CONTA-002, CONTA-003 e CONTA-005. Se houver liquidação D+2 pendente, o MO recusa a preparação e a medição não começa.

Sessão limpa. Os volumes nomeados seguem o nome do projeto Compose. Para não reaproveitar dados antigos:

- Pare a stack anterior com `docker compose stop`. Não use `down -v`.
- Suba com um nome de projeto novo (`docker compose -p <nome-novo> up -d --wait ...`). As portas em `127.0.0.1` são fixas, então apenas um projeto pode estar ativo.
- Confirme que os volumes são novos com `docker volume ls`.

Observação: o comando exato usado em 2026-10-28 não foi registrado no roadmap. O procedimento acima segue a estrutura do Compose e não foi reexecutado para este documento.

## Roteiro de demonstração

Pré-requisitos: stack no ar e `verificar-ambiente.py` aprovado. Duração aproximada: cinco minutos.

1. **Mercado.** Abra a página principal em http://localhost:4200. Mostre as cinco ações fictícias e o livro de ofertas de uma delas (ex.: ALFA3). Base: [mercado.spec.ts](../frontend/e2e/mercado.spec.ts).
2. **Boleta.** Envie uma ordem Limit, depois Market e Stop Limit. Mostre a ordem `RECEBIDA` e depois `RESERVADA` ou `REJEITADA`. Repita o envio com a mesma chave para mostrar a idempotência. Base: [boleta.spec.ts](../frontend/e2e/boleta.spec.ts) e [RF02–RF04](roadmap.md#rf02--envio-e-acompanhamento-inicial-de-ordens) do roadmap.
3. **Matching.** Execute o exemplo de matching pelo runner: `docker compose run --rm --no-deps -T runner-planilhas runner/exemplos/matching.xlsx --config runner/exemplos/compose.json`. A imagem usa `ENTRYPOINT` `node runner/cli.mjs`, e o argumento substitui o `CMD` padrão (`fundacao.xlsx`). Mostre os negócios no livro e a atualização do VWAP.
4. **Posições, razão e D+2.** Consulte posições e histórico na tela, com a conta da tela (CONTA-001, ADR-012). Base: [posicoes.spec.ts](../frontend/e2e/posicoes.spec.ts) e [historico.spec.ts](../frontend/e2e/historico.spec.ts). Use `GET /servicos/back-office/api/v1/posicoes`, `/negocios`, `/razao` e `/data-movimento` para ver o D+2 pelas APIs.
5. **Tempo real e trajetória.** Mostre a atualização por WebSocket em `/api/v1/atualizacoes` no FO e a trajetória com `GET /api/v1/ordens/{id}/trajetoria`. Planilha: `frontend/runner/exemplos/trajetoria.xlsx`.
6. **Cancelamento.** Cancele uma ordem aberta pela lista da boleta. Mostre o saldo liberado pelo backend. Base: E2E de cancelamento em [boleta.spec.ts](../frontend/e2e/boleta.spec.ts) e planilha `frontend/runner/exemplos/cancelamento.xlsx`.
7. **Painéis e alertas.** Abra o Grafana em http://localhost:13000 (usuário `admin`, senha em `GRAFANA_ADMIN_PASSWORD` no `.env`) e mostre os quatro dashboards de negócio. Em http://localhost:19090/alerts, mostre as regras e seu estado. Em http://localhost:16687, abra um trace de ordem no Jaeger.

Limites para citar na demonstração: a medição de latência é local, em WSL2, com um único salto de carga documentado; o p99 da boleta não atinge a meta; alertas não notificam ninguém.

## Fontes

[PRD-001](prds/PRD-001-simulador-prospero.md) (RNF01–RNF08 e aceite da V01), [roadmap](roadmap.md), [HLD-004](hlds/HLD-004-matching-engine.md), [ADR-012](adrs/ADR-012-risco-reservas-saga.md), [ADR-013](adrs/ADR-013-matching-execucao.md) e [ADR-018](adrs/ADR-018-publicacao-outbox-apos-commit.md), [README](../README.md) e [rules](rules.md).
