# Próspero — Angular

Interface local do simulador. Apresenta os quatro contextos, mercado sintético e boleta com acompanhamento de ordens. A RF05 conecta a execução ao risco e acrescenta a espera pelo gatilho de Stop/Stop Limit, em validação; OCO, posições e liquidação continuam pendentes. Estado e evidências no roadmap global.

## Executar e verificar

```bash
npm ci
npm test -- --watch=false
npm run format:check
npm run build
npm start
```

Node 24.16.0; Angular 22.2.3 e TypeScript 6.0. O dev server usa `proxy.conf.mjs` para as APIs em localhost:8081–8084 e injeta o token de leitura da RF01 (`FO_TOKEN_LEITURA`) e o de ordens da conta demo (`FO_TOKEN_ORDENS`, só GET/POST em `/api/v1/ordens*`, POST com Origin local), lidos do ambiente ou do `.env`; a preparação de mercado é bloqueada pelo proxy. O Nginx do container aplica as mesmas regras, além de recusar Host não local (421) e limitar a taxa de envio. No container, o build estático é servido por Nginx em 8080 e publicado em localhost:4200, com proxy por DNS Docker e mesma origem.

Consulte o [roadmap Angular](ROADMAP.md), o [README geral](../README.md) e os [contratos de API](../docs/adrs/ADR-003-contratos-api.md). O runner de planilhas da P03 é uma CLI em container, descrita abaixo.

## Runner XLSX — contrato P03

CLI Node independente do Angular, sem acesso a bancos, Kafka ou Redis. Formato v1: exatamente três abas visíveis, sem fórmulas, macros, células mescladas, hyperlinks ou objetos de célula. Primeira linha contém os cabeçalhos exatos abaixo, nesta ordem; demais linhas pertencem explicitamente a um cenário.

| Aba | Colunas |
| --- | --- |
| Carga e Passos | `cenario`, `ordem`, `id`, `servico`, `metodo`, `caminho`, `headers`, `corpo`, `capturas`, `intervaloMs`, `prazoMs` |
| Resultados | `cenario`, `passo`, `status`, `headers`, `corpo` |

Cenário/id/serviço/variável usam letras ASCII, números, `_` ou `-`, iniciando por letra, até 64 caracteres. Ordem é inteiro positivo único por aba/cenário. Executa Carga antes de Passos, ordenados por ordem. Cada passo exige exatamente uma linha de Resultados. Métodos: GET, POST, PUT, PATCH, DELETE. Headers são objeto JSON textual com nomes `accept`, `content-type` ou `x-…`; credenciais ficam na configuração externa. Corpo é JSON textual ou célula vazia (sem corpo), proibido em GET. Decimais financeiros e IDs extensos devem ser strings JSON; números JSON aceitam apenas literais inteiros decimais seguros; frações/expoentes são recusados pelo lexema original, também nas respostas, antes de qualquer comparação.

`capturas` é objeto JSON de nome para JSON Pointer (`{"id":"/id"}`) ou header (`{"correlacao":"@header:x-request-id"}`). `${id}` referencia uma captura de passo anterior do mesmo cenário; não existem referências futuras ou entre cenários. `${execucao}` e `${cenario}` são UUIDs novos a cada execução/cenário. Uma referência que ocupa toda uma string JSON preserva o tipo; interpolação parcial exige escalar. Em caminhos, valores são codificados e o caminho final é revalidado. Sem expressões ou execução de código.

Resultados: status HTTP inteiro 100–599; headers é objeto de strings comparadas sem distinguir maiúsculas nos nomes; corpo é mapa de JSON Pointer para valor JSON esperado, com igualdade tipada, por exemplo `{"/estado":"concluida","/saldo":"10.00"}`. Pointer vazio compara o corpo inteiro; ausência difere de null. `{}` ou célula vazia não exige campos. `204` aceita resposta sem corpo. Capturas só são publicadas após as asserções passarem.

Polling: intervaloMs e prazoMs vazios executam uma chamada; preenchidos exigem GET, intervalo 50–5000 ms e prazo 50–30000 ms. No GET, repetir até as asserções passarem; erro de transporte, redirecionamento ou limite encerra o cenário. Exceção explícita para preparação de risco: permite PUT somente no serviço `preparacao-risco`, caminho estático exato `/api/v1/preparacoes-risco/contas/CONTA-002` ou `CONTA-003`, resultado esperado 200 e intervalo/prazo preenchidos. Nesse caso repetir exclusivamente resposta 409 com objeto JSON cujo `codigo` seja `RESERVA_VIVA`; a recusa ocorre antes de qualquer alteração de carteira. Resposta 200 encerra tentativas e é validada uma vez, mesmo se divergente; demais erros/códigos, corpo inválido, transporte ou timeout não repetem a mutação. Caminhos interpolados, queries, outras contas/serviços/métodos e PUT esperado 409 não recebem essa exceção. Corpo/headers permanecem estáveis entre tentativas, capturas só no sucesso; cada tentativa consome os limites atuais. Preparação com resposta 409 esperada continua execução única. Falha interrompe o cenário, marca seus passos restantes como não executados e continua os demais. Não há rollback automático: eventual limpeza é um passo API explícito, sujeito à mesma interrupção.

Toda a planilha e configuração são validadas antes do primeiro HTTP, inclusive cenários não selecionados. Limites fixos: arquivo 1 MiB; ZIP 128 entradas/8 MiB expandidos e 2 MiB por entrada (medidos durante leitura); parser 10 s/128 MiB de heap em worker; 1000 linhas por aba, 16 KiB por célula, JSON profundidade 20; 20 cenários, 200 passos; 1 MiB por resposta e orçamento conservador de 1 MiB por expansão de corpo/headers/expectativas (até seis bytes por unidade UTF-16 para escapes; pode recusar textos menores), 4096 caracteres por header resolvido, 5 s por chamada, 60 s por cenário, 120 s por execução e 1000 chamadas. Origens exatas são fornecidas por configuração confiável, sem credenciais na URL, caminhos base, query ou fragmento. Planilha só escolhe serviço conhecido e caminho sob `/api/v1/`; traversal, barras invertidas, percentuais estáticos e redirecionamentos são recusados. A configuração externa não é sandbox de rede: use apenas origens locais confiáveis.

Relatório JSON em stdout contém cenário, passo, resultado, tentativas e divergências com esperado/observado por tipo (valores textuais e objetos omitidos), sem corpos/headers brutos. UUID de correlação é informado quando disponível. Erros estáticos são códigos limitados com aba/linha; nomes livres, dados de arquivo e credenciais não são ecoados. Exit 0: sucesso; 1: cenário falhou; 2: arquivo/configuração/CLI inválido; 130/143: cancelamento. SIGINT/SIGTERM cancela requisição/polling e impede novos passos.

Executar a partir de `frontend/` com a stack já disponível:

```bash
npm run test:runner
npm run runner -- runner/exemplos/fundacao.xlsx --config runner/exemplos/local.json
npm run runner -- runner/exemplos/fundacao.xlsx --config runner/exemplos/local.json --cenario front-office
```

Na raiz, container opt-in, sem iniciar dependências:

```bash
docker compose build runner-planilhas
docker compose run --rm --no-deps -T runner-planilhas
```

Para workbook/configuração privados, montar arquivos somente leitura e passar caminhos internos, por exemplo `docker compose run --rm --no-deps -T -v /caminho/cenario.xlsx:/entrada/cenario.xlsx:ro -v /caminho/config.json:/entrada/config.json:ro runner-planilhas /entrada/cenario.xlsx --config /entrada/config.json`. Não versionar configuração com credenciais. Redirecionar stdout para relatório privado, se desejado; o runner não grava arquivos. Container: 384 MiB, 1 CPU, 64 PIDs, usuário node, filesystem somente leitura e tmpfs 16 MiB, sem portas ou volumes padrão.

Resumo legível de `runner/exemplos/fundacao.xlsx`: quatro cenários independentes, um por serviço. Carga faz GET de identidade e captura `/servico`; Passos consulta de novo e compara a captura, versão `0.1.0-SNAPSHOT`, status 200 e `Cache-Control: no-store`. Não cria dados financeiros. O harness dos testes comprova carga POST com IDs novos, encadeamento e polling; as RFs entregarão os workbooks financeiros. `node runner/exemplos/gerar.mjs` regenera o binário a partir desse esquema (datas/metadados ZIP podem mudar).

`runner/exemplos/mercado.xlsx` (RF01, gerado por `gerar-mercado.mjs`) tem quatro cenários, cada um no namespace `${cenario}`: níveis/agregação/corte em cinco/indicadores, vazio e unilateral com nova revisão, rejeições sem escrita parcial e isolamento entre namespaces. Exige token de preparação na configuração (`--perfil mercado`, padrão). `runner/exemplos/ordens.xlsx` e `runner/exemplos/validacao.xlsx` (RF03: fronteiras do túnel, Stop/Stop Limit, lote/tick do OMEGA3, OCO com perna fora, Market sem base) usam `--perfil ordens`, com `ordens-a`/`ordens-b` (tokens das contas de teste CONTA-002/003; RF02: registro, idempotência repetida/divergente, isolamento entre contas, rejeições e OCO; ordens não são apagadas e as chaves derivam de `${cenario}`): `python3 scripts/mercado.py config-runner /caminho/runner.json --origem http://127.0.0.1:8081 [--perfil ordens]` e então `npm run runner -- runner/exemplos/mercado.xlsx --config /caminho/runner.json`. Uma falha interrompe o cenário antes da limpeza; o namespace é único por execução e pode ser removido com `DELETE /api/v1/preparacoes-mercado/{uuid}`.

O charset final do caminho é conservador: letras/números ASCII e `_ / ? = & . ~ -`; percentuais são recusados também após interpolação. Capturas com `/`, espaços, `%` ou caracteres que exigem encoding falham antes da chamada dependente. Essa restrição evita decodificação dupla; IDs UUID/ASCII funcionam. JSON e headers preservam o valor original. Limite de heap do worker não cobre toda a memória nativa; o teto do container complementa os limites de arquivo, ZIP e prazo.

`runner/exemplos/matching.xlsx` (RF05b/RF05c.2, gerado por `gerar-matching.mjs`, perfil `risco`) exercita Limit/IOC com execução parcial em dois preços e melhoria de preço, FOK atômica sem liquidez e Market em livro vazio. A RF05c.2 acrescenta Stop de compra integral/parcial, Stop de venda, Stop Limit que repousa após o gatilho e executa como passiva, além de combinações inválidas. Cada cenário prepara CONTA-002/003 e encerra suas ofertas por negócios reais. Compara progresso do FO, caixa/custódia do MO, devolução do restante e recusa HTTP 409 ao preparar carteira com reserva viva. Não credita caixa ao vendedor nem custódia ao comprador (RF08). Pode ser repetido, inclusive duas vezes na CI, quando as contas não têm outras reservas ou ofertas.

`risco.xlsx` acompanha o contrato RF05b: Limit simples chega a ABERTA, Market sem contraparte termina CANCELADA e a limpeza das ofertas usa autonegociação pela API, com débito normal dos dois recursos. O cenário OCO final usa CONTA-003 e preserva sua reserva: OCO aguarda a próxima fatia da RF05c. Essa planilha exige carteiras sem reservas prévias e não pode ser repetida após o cenário OCO na mesma sessão. A API de preparação preserva reservas/histórico e recusa qualquer ATIVA/PARCIAL com 409. Saldo reservado zero e terminal no FO não garantem que o MO já consumiu o terminal: as preparações com sucesso esperado de `matching.xlsx` e `risco.xlsx` usam a espera explícita restrita acima, sem liberar recursos por timeout. A CI executa primeiro matching duas vezes, recuperação/concorrência e depois as provas que deixam Stop/OCO; não apaga livro nem reservas para contornar essa fronteira.

Na raiz, `python3 scripts/mercado.py verificar-matching` usa as APIs locais para executar parte de uma oferta, reinicia somente o ME, reentrega a admissão original e executa o restante. Compara ordens e recursos e confirma ausência de oferta/débito duplicado. A prova interna de recuperação lê a outbox do MO e publica no Kafka; esse acesso não faz parte do runner XLSX. Use `--projeto prospero-ci-<run>-<attempt>` para a stack descartável da CI ou omita para a stack local, sempre com CONTA-002/003 livres. A recuperação de stops simples é verificada também pelos testes internos da RF05c.2; OCO e fechamento integrado continuam posteriores.
