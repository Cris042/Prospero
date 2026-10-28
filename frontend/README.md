# Próspero — Angular

Interface local do simulador. Apresenta mercado real do matching, boleta com acompanhamento de ordens e posições; a identidade dos quatro serviços segue disponível pelo Swagger de cada um. A RF05 integra execução, risco, Stop/Stop Limit e OCO; posições integradas na RF07; histórico e liquidação RF08e em validação. Estado e evidências no roadmap global.

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

## Tema claro e escuro

As cores ficam em variáveis CSS em `src/styles.css`; `:root` define o tema claro e o escuro reutiliza os mesmos nomes. Sem escolha salva, a página segue `prefers-color-scheme`. O botão “Tema escuro” da barra superior (`aria-pressed`) fixa a escolha em `data-theme` no `<html>` e a salva em `localStorage` (`prospero-tema`); um script inline no `index.html` aplica a escolha antes do primeiro paint. Com armazenamento bloqueado, a troca vale só na sessão.

## Atualização do mercado — RF06b

Ativos e livro selecionado têm ciclos GET independentes, com nova consulta um segundo após terminar a anterior e timeout de oito segundos. Erros, inclusive 429, recuam por 1/2/4/8 segundos (máximo oito); o sucesso restaura o ritmo normal. Ocultar a aba cancela as consultas e timers; voltar à aba consulta imediatamente. Destruir o componente encerra os ciclos. Atualização manual não interrompe chamada pendente nem antecipa o recuo após erro.

O último snapshot permanece visível durante atualização ou falha temporária, acompanhado de aviso. Trocar ativo remove imediatamente o livro anterior. Origem, sessão, revisão e defasagem vêm da respectiva resposta; resumo e livro podem representar revisões diferentes. Silêncio de negócios não significa defasagem. VWAP chega como texto com quatro casas, sem cálculo ou arredondamento no navegador; “—” significa ausência. A interface distingue mercado real de fixture sintética.

No Nginx, resumo e livro compartilham limite de cinco requisições/s por IP, burst dez e resposta 429 com `Cache-Control: no-store`. Abas no mesmo IP compartilham a cota; ela não alcança POST de ordens. O proxy de desenvolvimento restringe leitura a GET/HEAD e Host local, com token apenas no servidor; não replica a cota do Nginx. Esses parâmetros limitam abuso no laboratório e não comprovam capacidade ou RNF04. Retornar à imagem frontend anterior preserva bancos e projeção.

A RF06b altera apresentação e ritmo de consulta; não muda contrato ou cálculo financeiro. Conforme ADR-004, dispensa nova planilha: `cotacao.xlsx` preserva a prova financeira por APIs, complementada pelos testes de tempo controlado e E2E de atualização visual sem clique. A CI disponibiliza somente capturas PNG de `test-results` como artefato por sete dias, sem traces, configuração ou credenciais.

## Trajetória e atualizações — RF09

Em “Minhas ordens”, “Ver trajetória” abre os marcos confirmados FO/MO/ME/BO da ordem selecionada, incluindo instante de ocorrência e observação, motivo e identificadores técnicos em detalhes. A consulta HTTP tem páginas de20, até100 eventos na tela; ao atingir o limite, informa a visão parcial. Ordens anteriores à trajetória durável aparecem com histórico incompleto. Erro preserva a última consulta e permite tentar novamente; a interface não infere contabilização a partir da execução no ME.

Um WebSocket compartilhado avisa a revisão da conta, sem carregar valores financeiros. A abertura e a reconexão provocam nova consulta HTTP; avisos durante uma consulta são agrupados em uma consulta posterior, sem sobreposição. Revisões são comparadas como inteiros textuais, inclusive acima de2⁵³. A aba oculta fecha o canal; ao voltar, reconecta com recuo1/2/4/8s e jitter. Ordens mantêm atualização manual e posições conservam consultas periódicas de2s. Falha do canal mostra aviso, sem apresentar conexão como confirmação financeira.

Nginx e proxydev encaminham o upgrade somente em `/servicos/front-office/api/v1/atualizacoes`, com Origin HTTP local igual ao Host público, sem query ou subprotocolo. Injetam `FO_TOKEN_ORDENS` no servidor; o navegador não recebe a credencial. O backend exige JWT válido, conta conhecida e `ordens:leitura`, limita8 conexões/conta e32 globais e encerra a sessão no vencimento do JWT. Um envio pendente por conexão, timeout3s, mensagem máxima1024bytes e ping15s limitam o canal; clientes lentos recuperam por HTTP. Contrato completo no [ADR-017](../docs/adrs/ADR-017-trajetoria-tempo-real.md). A composição real, captura visual e trace são aceites pendentes enquanto a RF09b estiver em validação no roadmap.

## Posições e resultado — RF07

A seção da conta demo mostra posição dos negócios desde o início da simulação, preço médio, realizado acumulado e não realizado. A custódia inicial e a liquidação não entram nesse cálculo. Autonegociação preserva o resultado da própria conta. Valores chegam textuais; o frontend não calcula dinheiro com `Number`.

A consulta usa `/servicos/back-office/api/v1/posicoes`, token `BO_TOKEN_POSICOES` somente no proxy, audiência/escopo BO e conta definida pelo token. GET/HEAD na rota exata; Authorization recebido é descartado nas rotas genéricas. Rode `python3 scripts/preparar-ambiente.py` na raiz para gerar/renovar a configuração local sem expor credenciais e reconstrua/reinicie os containers afetados.

Atualização a cada2 s, sem sobreposição e com timeout8 s; ocultar/destruir cancela consultas. Falha ou503 preserva o snapshot anterior com aviso, sem mostrar zeros como confirmação. Cotação ausente ou defasada tem rótulo; a tabela possui rolagem própria no mobile e foco visível, sem anúncio acessível a cada tick.

`runner/exemplos/posicoes.xlsx` cobre, por APIs reais, médio periódico, ampliação/redução, viradas long/short, zeragem, autonegociação e recusas de autenticação. Perfil isolado antes de outras negociações DELTA3; duas repetições fecham quantidade/PnL em zero e restauram preço40, sem apagar histórico. Não usar contra histórico arbitrário. Gerador em `gerar-posicoes.mjs`.

E2E integrado exige `PROSPERO_POSICOES_E2E_CONFIG` apontando ao arquivo privado gerado por `scripts/mercado.py config-runner --perfil posicoes`, com origens loopback acessíveis ao Node. A CI gera esse arquivo0600 em diretório temporário0700, mantém a contraparte autenticada no Node e nunca no navegador; o cenário observa a posição mudar após negócio real e fecha por outro negócio. Upload contém somente PNG, não a configuração ou traces. Evidências pertencem ao PR.

## Negócios e razão — RF08b

Tela de histórico acrescentada na RF08e. O proxy injeta `BO_TOKEN_RAZAO` (conta demo, audiência BO, escopo `razao:leitura`) somente em GET/HEAD de `/servicos/back-office/api/v1/negocios`, `/negocios/{id}` (um segmento; o BO responde o mesmo 404 para inexistente e alheio) e `/razao`; demais extensões são recusadas e Authorization do cliente é descartado.

`runner/exemplos/razao.xlsx` (gerado por `gerar-razao.mjs`, perfil `--perfil razao`) roda logo após `posicoes.xlsx`: recusas 401/403/400/404, prepara CONTA-002/003, abre 2 DELTA3 a 40,00, confere o negócio de cada lado pela ordem capturada, data de sessão e de liquidação coerentes entre os lados, detalhe, 404 para CONTA-001, as quatro partidas D0 de cada lado contra a Câmara, paginação por cursor e cursor de outra conta 400; fecha por negócio inverso, com posição/PnL zero e cotação 40. Repetível duas vezes na CI; não usar contra histórico arbitrário com outras negociações DELTA3 simultâneas. O D+2 exato é provado pelos testes com datas fixas.

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

## Histórico e liquidação — RF08e

A seção Histórico e liquidação consulta negócios e razão da conta demo pelo proxy autenticado do BO, sem token no navegador. Cada seção carrega 20 itens por página, com teto de 100; Atualizar histórico volta à primeira página. Estado A liquidar ou Liquidado vem do BO e não comprova sozinho o crédito MO. Consultas têm timeout de oito segundos; falha mantém os últimos dados com aviso. A tela não avança a data de movimento.

`liquidacao.xlsx` verifica estado D+2, crédito MO do robô e repetição sem alterar carteira, depois de `scripts/liquidacao.py` conciliar movimentos de todas as contas. O perfil `liquidacao` do config-runner limita os tokens a leitura CONTA-004 e administração do movimento, no processo CLI. A CI roda a planilha duas vezes ao fim das negociações, com guarda de pendência ativa; as planilhas D0 anteriores usam a exceção legada explicitamente documentada no ADR-016. Para uso local, liquidar e esperar créditos antes de preparar novamente uma carteira.

RF09a: `runner/exemplos/trajetoria.xlsx` (gerador `gerar-trajetoria.mjs`, perfil `risco`) prepara saldo zero na CONTA-002, envia Market IOC e verifica os marcos FO/MO, motivo, paginação e isolamento por conta. A CI isolada o executa duas vezes após a razão, antes dos cenários com reservas vivas. Esse workbook cobre rejeição; confirmações de negócio/atomicidade são verificadas em PostgreSQL e a composição com WebSocket, interface e trace será entregue pela RF09b. Não usar a preparação sobre uma carteira com pendências; liquidar e confirmar os créditos primeiro.

RF10c3: `runner/exemplos/cancelamento.xlsx` (gerador `gerar-cancelamento.mjs`, perfil `risco`) cobre cancelamento manual por PUT/GET: sem execução com corpo inválido, outra conta, `{}` e repetição; parcial preservando negócios; Stop pendente; Stop Limit acionado com saldo; recusa 422 de ordem OCO, inclusive a perna que sobrevive. Espera o `CONFIRMADO` do middle-office antes de conferir carteira (reserva zerada, caixa e custódia). A CI o executa duas vezes após `matching.xlsx`; cada cenário termina sem reserva ou oferta viva.
