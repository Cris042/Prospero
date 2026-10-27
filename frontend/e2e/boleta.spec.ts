import { expect, test, type APIRequestContext, type Page } from '@playwright/test';

function caminhoSemConsulta(url: string): string {
  try {
    return new URL(url).pathname;
  } catch {
    return '(sem URL)';
  }
}

const POSICOES = '/servicos/back-office/api/v1/posicoes';
const LEITURAS_BO: Record<string, string> = {
  [POSICOES]: 'POSICOES_INDISPONIVEIS',
  '/servicos/back-office/api/v1/negocios': 'RAZAO_INDISPONIVEL',
  '/servicos/back-office/api/v1/razao': 'RAZAO_INDISPONIVEL',
};
const ERRO_RECURSO_503 =
  'Failed to load resource: the server responded with a status of 503 (Service Unavailable)';

function leituraDaPagina(url: string, page: Page): string | null {
  try {
    const destino = new URL(url);
    const query = destino.pathname === POSICOES ? '' : '?limite=20';
    return destino.origin === new URL(page.url()).origin &&
      Object.hasOwn(LEITURAS_BO, destino.pathname) &&
      destino.search === query
      ? destino.pathname
      : null;
  } catch {
    return null;
  }
}

/** Só aceita 503 contratado de leitura BO quando o navegador e a UI comprovam recuperação. */
function observarRecuperacao(page: Page) {
  const erros: string[] = [];
  const respostasComErro: { metodo: string; caminho: string; status: number }[] = [];
  const pendentes = new Set<Promise<void>>();
  const ultimaFalha = new Map<string, number>();
  const ultimoSucesso = new Map<string, number>();
  const indisponibilidades = new Map<string, number>();
  const mensagens503 = new Map<string, number>();
  let sequencia = 0;
  page.on('pageerror', (erro) => erros.push(erro.message));
  page.on('console', (mensagem) => {
    if (mensagem.type() !== 'error') return;
    const leitura = leituraDaPagina(mensagem.location().url, page);
    if (leitura && mensagem.text() === ERRO_RECURSO_503)
      mensagens503.set(leitura, (mensagens503.get(leitura) ?? 0) + 1);
    else erros.push(`${caminhoSemConsulta(mensagem.location().url)}: ${mensagem.text()}`);
  });
  page.on('response', (resposta) => {
    const ordem = ++sequencia;
    const caminho = caminhoSemConsulta(resposta.url());
    const metodo = resposta.request().method();
    const leitura = metodo === 'GET' ? leituraDaPagina(resposta.url(), page) : null;
    // Não aguardar Response.finished: respostas roteadas podem ficar pendentes no Playwright
    // mesmo após Angular consumir o corpo. O 200 posterior E a UI recuperada são obrigatórios.
    if (leitura && resposta.status() === 200) ultimoSucesso.set(leitura, ordem);
    if (resposta.status() < 400) return;
    if (respostasComErro.length >= 20) {
      if (!erros.includes('Mais de 20 respostas HTTP com erro'))
        erros.push('Mais de 20 respostas HTTP com erro');
      return;
    }
    // Somente metadados: nunca registrar headers, query strings ou corpos com credenciais.
    respostasComErro.push({ metodo, caminho, status: resposta.status() });
    if (!leitura || resposta.status() !== 503) {
      erros.push(`HTTP ${resposta.status()} ${metodo} ${caminho}`);
      return;
    }
    ultimaFalha.set(leitura, ordem);
    const verificacao = (async () => {
      try {
        const problema: unknown = await resposta.json();
        if (
          resposta.headers()['content-type']?.split(';')[0] === 'application/problem+json' &&
          problema !== null &&
          typeof problema === 'object' &&
          'status' in problema &&
          problema.status === 503 &&
          'codigo' in problema &&
          problema.codigo === LEITURAS_BO[leitura]
        ) {
          indisponibilidades.set(leitura, (indisponibilidades.get(leitura) ?? 0) + 1);
          return;
        }
      } catch {
        // Corpo inválido também reprova, sem expor seu conteúdo no diagnóstico.
      }
      erros.push(`503 fora do contrato ${metodo} ${caminho}`);
    })();
    pendentes.add(verificacao);
    void verificacao.finally(() => pendentes.delete(verificacao));
  });
  return async (timeout = 15000) => {
    const historico = page.locator('app-historico');
    let reconsultouHistorico = false;
    await expect
      .poll(
        async () => {
          const falhouHistorico = [...indisponibilidades.keys()].some(
            (rota) =>
              rota !== POSICOES && (ultimoSucesso.get(rota) ?? 0) <= (ultimaFalha.get(rota) ?? 0),
          );
          const atualizar = historico.getByRole('button', {
            name: 'Atualizar histórico',
            exact: true,
          });
          // O produto oferece recuperação manual para histórico. Uma tentativa limitada pela UI;
          // outra recusa continua falhando, sem fabricar sucesso ou gerar polling ilimitado.
          if (
            !reconsultouHistorico &&
            falhouHistorico &&
            (await historico.getByRole('alert').count()) > 0 &&
            (await atualizar.isEnabled())
          ) {
            reconsultouHistorico = true;
            await atualizar.click({ timeout });
          }
          return {
            erros,
            respostasPendentes: pendentes.size,
            console503Correlacionado: [...mensagens503].every(
              ([rota, total]) => total <= (indisponibilidades.get(rota) ?? 0),
            ),
            recuperou: Object.keys(LEITURAS_BO).every(
              (rota) => (ultimoSucesso.get(rota) ?? 0) > (ultimaFalha.get(rota) ?? 0),
            ),
            tabelaVisivel: await page
              .getByRole('region', { name: 'Tabela de posições' })
              .isVisible(),
            historicoVisivel:
              ((await historico.getByRole('region', { name: 'Tabela de negócios' }).isVisible()) ||
                (await historico
                  .getByText('Nenhum negócio registrado para esta conta.', { exact: true })
                  .isVisible())) &&
              ((await historico.getByRole('region', { name: 'Tabela da razão' }).isVisible()) ||
                (await historico
                  .getByText('Nenhum lançamento registrado para esta conta.', { exact: true })
                  .isVisible())),
            alertas: await page.locator('app-posicoes, app-historico').getByRole('alert').count(),
          };
        },
        { timeout, message: JSON.stringify(respostasComErro) },
      )
      .toEqual({
        erros: [],
        respostasPendentes: 0,
        console503Correlacionado: true,
        recuperou: true,
        tabelaVisivel: true,
        historicoVisivel: true,
        alertas: 0,
      });
  };
}

function registroDaBoleta(page: Page) {
  return page
    .locator('app-boleta')
    .getByRole('status')
    .filter({ hasText: /registrad/ });
}

function erroDaBoleta(page: Page, texto: string) {
  return page.locator('app-boleta').getByRole('alert').filter({ hasText: texto });
}

interface CotacaoAlfa {
  ticker: string;
  ultimoPreco: string | null;
  precoReferencia: string;
  tick: string;
  volumeQuantidade: number | null;
}

function centavos(preco: string): bigint {
  expect(preco).toMatch(/^\d+\.\d{2}$/);
  return BigInt(preco.replace('.', ''));
}

function decimal(valor: bigint): string {
  return `${valor / 100n}.${(valor % 100n).toString().padStart(2, '0')}`;
}

function faixaAtual(cotacao: CotacaoAlfa) {
  const base = centavos(cotacao.ultimoPreco ?? cotacao.precoReferencia);
  const tick = centavos(cotacao.tick);
  expect(tick).toBeGreaterThan(0n);
  const divisor = 100n * tick;
  const minimo = ((base * 90n + divisor - 1n) / divisor) * tick;
  const maximo = ((base * 110n) / divisor) * tick;
  const gatilho = ((base * 95n + divisor - 1n) / divisor) * tick;
  return { minimo, maximo, gatilho, tick };
}

async function consultarAlfa(request: APIRequestContext): Promise<CotacaoAlfa> {
  const resposta = await request.get('/servicos/front-office/api/v1/ativos');
  expect(resposta.status()).toBe(200);
  const ativos: CotacaoAlfa[] = await resposta.json();
  const alfa = ativos.find((ativo) => ativo.ticker === 'ALFA3');
  expect(alfa).toBeDefined();
  return alfa!;
}

test('registra ordem Limit pela boleta, sem credencial no navegador nem envio duplicado', async ({
  page,
}, testInfo) => {
  const verificarRecuperacao = observarRecuperacao(page);
  const posts: { autorizacao?: string; chave?: string }[] = [];
  page.on('request', (pedido) => {
    if (pedido.method() === 'POST' && pedido.url().endsWith('/api/v1/ordens')) {
      posts.push({
        autorizacao: pedido.headers()['authorization'],
        chave: pedido.headers()['x-idempotency-key'],
      });
    }
  });

  await page.goto('/');
  const boleta = page.getByRole('form', { name: 'Ordem simples' });
  await boleta.getByLabel('Ativo').selectOption('BETA3');
  await boleta.getByLabel('Quantidade').fill('25');
  await boleta.getByLabel('Preço limite (R$)').fill('19.75');
  const urlInicial = page.url();
  const navegacoes: string[] = [];
  page.on('framenavigated', (frame) => {
    if (frame === page.mainFrame()) navegacoes.push(frame.url());
  });
  const resposta = page.waitForResponse(
    (r) => r.url().endsWith('/api/v1/ordens') && r.request().method() === 'POST',
  );
  const liberacao = Promise.withResolvers<void>();
  let registrada!: { id: string };
  await page.route('**/servicos/front-office/api/v1/ordens', async (rota) => {
    if (rota.request().method() !== 'POST') return rota.fallback();
    const respostaReal = await rota.fetch();
    // Captura o corpo real antes de entregá-lo ao navegador; o duplo clique ocorre com envio pendente.
    registrada = await respostaReal.json();
    await liberacao.promise;
    await rota.fulfill({ response: respostaReal });
  });
  try {
    await boleta.getByRole('button', { name: 'Registrar ordem' }).dblclick();
  } finally {
    liberacao.resolve();
  }
  const respostaRegistro = await resposta;
  expect(respostaRegistro.status()).toBe(202);
  const status = registroDaBoleta(page);
  await expect(status).toContainText(/Ordem registrada — estado (Recebida|Reservada|Aberta)\./);
  // A boleta acompanha o risco; o ME pode já ter aberto a ordem antes dessa consulta.
  await expect(status).toContainText(/Ordem registrada — estado (Reservada|Aberta)\./, {
    timeout: 15000,
  });
  await expect
    .poll(
      async () => {
        const consulta = await page.request.get(
          `/servicos/front-office/api/v1/ordens/${registrada.id}`,
        );
        expect(consulta.status()).toBe(200);
        return (await consulta.json()).estado;
      },
      { timeout: 15000 },
    )
    .toBe('ABERTA');
  await expect(status).toContainText('Compra 25 BETA3');
  await expect(page.getByRole('region', { name: 'Minhas ordens' })).toContainText('19,75');
  expect(posts).toHaveLength(1);
  await expect(page).toHaveURL(urlInicial);
  expect(navegacoes).toEqual([]);
  expect(posts[0].autorizacao).toBeUndefined();
  expect(posts[0].chave).toMatch(/^[0-9a-f-]{36}$/);
  await expect(status).not.toContainText('Executada');
  await verificarRecuperacao();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await page.getByRole('heading', { name: 'Boleta' }).scrollIntoViewIfNeeded();
  await page.screenshot({ path: testInfo.outputPath('boleta.png'), fullPage: true });
});

test('recupera posições após um 503 contratado sem esconder erros da boleta', async ({ page }) => {
  const verificarRecuperacao = observarRecuperacao(page);
  const liberacao = Promise.withResolvers<void>();
  let primeira = true;
  await page.route(`**${POSICOES}`, async (rota) => {
    if (primeira) {
      primeira = false;
      await rota.fulfill({
        status: 503,
        contentType: 'application/problem+json',
        body: JSON.stringify({ status: 503, codigo: 'POSICOES_INDISPONIVEIS' }),
      });
      return;
    }
    await liberacao.promise;
    await rota.fallback();
  });
  try {
    await page.goto('/');
    await expect(page.locator('app-posicoes').getByRole('alert')).toContainText(
      'Tentaremos novamente automaticamente',
    );
  } finally {
    liberacao.resolve();
  }
  // A resposta posterior passa pela API real; nenhum clique em Atualizar posições.
  await verificarRecuperacao();
});

for (const recurso of [
  { rota: '/servicos/back-office/api/v1/negocios', nome: 'negócios' },
  { rota: '/servicos/back-office/api/v1/razao', nome: 'razão' },
]) {
  test(`recupera histórico após um 503 contratado em ${recurso.nome} pela UI`, async ({ page }) => {
    const verificarRecuperacao = observarRecuperacao(page);
    let consultas = 0;
    await page.route(`**${recurso.rota}?limite=20`, async (rota) => {
      if (++consultas === 1) {
        await rota.fulfill({
          status: 503,
          contentType: 'application/problem+json',
          body: JSON.stringify({ status: 503, codigo: 'RAZAO_INDISPONIVEL' }),
        });
      } else await rota.fallback();
    });
    await page.goto('/');
    await expect(page.locator('app-historico').getByRole('alert')).toContainText(recurso.nome);
    await verificarRecuperacao();
    expect(consultas).toBe(2);
  });
}

test('reprova histórico com 503 contratado persistente após uma atualização manual', async ({
  page,
}) => {
  const verificarRecuperacao = observarRecuperacao(page);
  let consultas = 0;
  await page.route('**/servicos/back-office/api/v1/negocios?limite=20', (rota) => {
    consultas++;
    return rota.fulfill({
      status: 503,
      contentType: 'application/problem+json',
      body: JSON.stringify({ status: 503, codigo: 'RAZAO_INDISPONIVEL' }),
    });
  });
  await page.goto('/');
  await expect(page.locator('app-historico').getByRole('alert')).toBeVisible();
  await expect(verificarRecuperacao(250)).rejects.toThrow();
  expect(consultas).toBe(2);
});

for (const falha of [
  {
    nome: '503 contratado persistente',
    status: 503,
    codigo: 'POSICOES_INDISPONIVEIS',
    persistente: true,
  },
  { nome: '500 de posições', status: 500, codigo: 'POSICOES_INDISPONIVEIS', persistente: false },
  { nome: '503 com código inesperado', status: 503, codigo: 'ERRO_HTTP', persistente: false },
]) {
  test(`reprova verificação da boleta com ${falha.nome}`, async ({ page }) => {
    const verificarRecuperacao = observarRecuperacao(page);
    const liberacao = Promise.withResolvers<void>();
    let primeira = true;
    await page.route(`**${POSICOES}`, async (rota) => {
      if (primeira || falha.persistente) {
        primeira = false;
        await rota.fulfill({
          status: falha.status,
          contentType: 'application/problem+json',
          body: JSON.stringify({ status: falha.status, codigo: falha.codigo }),
        });
        return;
      }
      await liberacao.promise;
      await rota.fallback();
    });
    try {
      await page.goto('/');
      await expect(page.locator('app-posicoes').getByRole('alert')).toBeVisible();
    } finally {
      liberacao.resolve();
    }
    if (!falha.persistente) {
      await expect(page.getByRole('region', { name: 'Tabela de posições' })).toBeVisible({
        timeout: 15000,
      });
      await expect(page.locator('app-posicoes').getByRole('alert')).toHaveCount(0);
    }
    // Mesmo após a UI recuperar, 500 e código inesperado continuam reprovando.
    await expect(verificarRecuperacao(250)).rejects.toThrow();
  });
}

test('reprova verificação da boleta com erro JavaScript mesmo após posições recuperarem', async ({
  page,
}) => {
  const verificarRecuperacao = observarRecuperacao(page);
  await page.goto('/');
  await verificarRecuperacao();
  const erro = page.waitForEvent('pageerror');
  await page.evaluate(() => {
    const frame = document.createElement('iframe');
    frame.srcdoc = '<script>throw new Error("Falha JavaScript de controle QA")</script>';
    document.body.appendChild(frame);
  });
  await erro;
  await expect(verificarRecuperacao(250)).rejects.toThrow();
});

test('mostra recusa do backend de forma acessível e registra OCO', async ({ page }) => {
  await page.goto('/');
  const boleta = page.getByRole('form', { name: 'Ordem simples' });
  await boleta.getByLabel('Preço limite (R$)').fill('1e3');
  await boleta.getByRole('button', { name: 'Registrar ordem' }).click();
  await expect(erroDaBoleta(page, 'Preço inválido')).toContainText('Preço inválido');
  const faixa = faixaAtual(await consultarAlfa(page.request));
  await boleta.getByLabel('Preço limite (R$)').fill(decimal(faixa.maximo + faixa.tick));
  let corpoRecusa!: unknown;
  let grupoOco!: { grupoOco: string };
  const urlInicial = page.url();
  const navegacoes: string[] = [];
  page.on('framenavigated', (frame) => {
    if (frame === page.mainFrame()) navegacoes.push(frame.url());
  });
  await page.route(/\/api\/v1\/ordens(?:-oco)?$/, async (rota) => {
    if (rota.request().method() !== 'POST') return rota.fallback();
    const respostaReal = await rota.fetch();
    const corpo = await respostaReal.json();
    if (rota.request().url().endsWith('/ordens-oco')) {
      expect(respostaReal.status()).toBe(202);
      grupoOco = corpo;
    } else {
      expect(respostaReal.status()).toBe(422);
      corpoRecusa = corpo;
    }
    await rota.fulfill({ response: respostaReal });
  });
  const recusa = page.waitForResponse(
    (r) => r.url().endsWith('/api/v1/ordens') && r.request().method() === 'POST',
  );
  await boleta.getByRole('button', { name: 'Registrar ordem' }).click();
  const respostaRecusa = await recusa;
  expect(respostaRecusa.status()).toBe(422);
  expect(corpoRecusa).toMatchObject({
    codigo: 'PRECO_FORA_DO_TUNEL',
    detalhes: { minimo: decimal(faixa.minimo), maximo: decimal(faixa.maximo) },
  });
  await expect(erroDaBoleta(page, 'Faixa aceita:')).toContainText(
    `Faixa aceita: ${decimal(faixa.minimo).replace('.', ',')} a ${decimal(faixa.maximo).replace('.', ',')}.`,
  );

  await page.getByRole('button', { name: 'OCO (Take Profit + Stop Loss)' }).click();
  const oco = page.getByRole('form', { name: 'Ordem OCO' });
  await oco.getByLabel('Alvo — Take Profit (R$)').fill(decimal(faixa.maximo));
  await oco.getByLabel('Preço de disparo (R$)').fill(decimal(faixa.gatilho));
  const respostaOco = page.waitForResponse(
    (r) => r.url().endsWith('/api/v1/ordens-oco') && r.request().method() === 'POST',
  );
  await oco.getByRole('button', { name: 'Registrar OCO' }).click();
  const registroOco = await respostaOco;
  expect(registroOco.status()).toBe(202);
  expect(grupoOco).toBeDefined();
  // A próxima prova Market100 encontra o TP100 em repouso também no projeto mobile.
  await expect
    .poll(
      async () => {
        const consulta = await page.request.get(
          `/servicos/front-office/api/v1/ordens-oco/${grupoOco.grupoOco}`,
        );
        expect(consulta.status()).toBe(200);
        const grupo = await consulta.json();
        return grupo.ordens.find((ordem: { papelOco: string }) => ordem.papelOco === 'TAKE_PROFIT')
          ?.estado;
      },
      { timeout: 15000 },
    )
    .toBe('ABERTA');
  await expect(registroDaBoleta(page)).toContainText('Take Profit: Venda 100 ALFA3');
  await expect(registroDaBoleta(page)).toContainText('Stop Loss: Venda 100 ALFA3');
  await expect(page).toHaveURL(urlInicial);
  expect(navegacoes).toEqual([]);
});

test('envia Market e Stop Limit e repete com a mesma chave após falha de rede', async ({
  page,
}) => {
  let market!: { id: string; quantidade: number };
  let stopLimit!: { tipo: string; precoLimite: string; precoGatilho: string };
  const chaves: string[] = [];
  page.on('request', (pedido) => {
    if (pedido.method() === 'POST' && pedido.url().endsWith('/api/v1/ordens')) {
      chaves.push(pedido.headers()['x-idempotency-key']);
    }
  });
  await page.goto('/');
  const urlInicial = page.url();
  const navegacoes: string[] = [];
  page.on('framenavigated', (frame) => {
    if (frame === page.mainFrame()) navegacoes.push(frame.url());
  });
  await page.route('**/servicos/front-office/api/v1/ordens', async (rota) => {
    if (rota.request().method() !== 'POST') return rota.fallback();
    const respostaReal = await rota.fetch();
    expect(respostaReal.status()).toBe(202);
    // Retém o corpo real antes de entregá-lo ao navegador, sem depender do buffer CDP.
    const corpo = await respostaReal.json();
    if (rota.request().postDataJSON().tipo === 'MARKET') market = corpo;
    if (rota.request().postDataJSON().tipo === 'STOP_LIMIT') stopLimit = corpo;
    await rota.fulfill({ response: respostaReal });
  });
  const boleta = page.getByRole('form', { name: 'Ordem simples' });
  await boleta.getByLabel('Tipo').selectOption('MARKET');
  await expect(boleta.getByLabel('Validade').locator('option')).toHaveText([
    'IOC (executa ou cancela)',
    'FOK (tudo ou nada)',
  ]);
  await expect(boleta.getByLabel('Preço limite (R$)')).toHaveCount(0);
  const antesMarket = await consultarAlfa(page.request);
  const respostaMarket = page.waitForResponse(
    (r) => r.url().endsWith('/api/v1/ordens') && r.request().method() === 'POST',
  );
  await boleta.getByRole('button', { name: 'Registrar ordem' }).click();
  const registroMarket = await respostaMarket;
  expect(registroMarket.status()).toBe(202);
  expect(market).toBeDefined();
  await expect(registroDaBoleta(page)).toContainText('Mercado');
  // Consumidores de ordens e cotação são distintos: esperar ambos antes de derivar o próximo preço.
  await expect
    .poll(
      async () => {
        const resposta = await page.request.get(
          `/servicos/front-office/api/v1/ordens/${market.id}`,
        );
        expect(resposta.status()).toBe(200);
        const ordem = await resposta.json();
        return { estado: ordem.estado, executada: ordem.quantidadeExecutada };
      },
      { timeout: 15000 },
    )
    .toEqual({ estado: 'EXECUTADA', executada: market.quantidade });
  const volumeEsperado = BigInt(antesMarket.volumeQuantidade ?? 0) + BigInt(market.quantidade);
  await expect
    .poll(async () => String((await consultarAlfa(page.request)).volumeQuantidade), {
      timeout: 15000,
    })
    .toBe(String(volumeEsperado));
  const faixa = faixaAtual(await consultarAlfa(page.request));

  await boleta.getByLabel('Tipo').selectOption('STOP_LIMIT');
  await expect(boleta.getByLabel('Validade').locator('option')).toHaveText(['Dia']);
  await boleta.getByLabel('Preço limite (R$)').fill(decimal(faixa.gatilho + faixa.tick));
  await boleta.getByLabel('Preço de disparo (R$)').fill(decimal(faixa.gatilho));
  let falhou = false;
  await page.route('**/servicos/front-office/api/v1/ordens', (rota) => {
    if (rota.request().method() === 'POST' && !falhou) {
      falhou = true;
      return rota.abort('connectionreset');
    }
    return rota.fallback();
  });
  await boleta.getByRole('button', { name: 'Registrar ordem' }).click();
  await expect(erroDaBoleta(page, 'o reenvio não duplica a ordem')).toContainText(
    'o reenvio não duplica a ordem',
  );
  const respostaStopLimit = page.waitForResponse(
    (r) => r.url().endsWith('/api/v1/ordens') && r.request().method() === 'POST',
  );
  await boleta.getByRole('button', { name: 'Registrar ordem' }).click();
  const registroStopLimit = await respostaStopLimit;
  expect(registroStopLimit.status()).toBe(202);
  expect(stopLimit).toMatchObject({
    tipo: 'STOP_LIMIT',
    precoLimite: decimal(faixa.gatilho + faixa.tick),
    precoGatilho: decimal(faixa.gatilho),
  });
  await expect(registroDaBoleta(page)).toContainText('Stop Limit');
  expect(chaves).toHaveLength(3);
  expect(chaves[2]).toBe(chaves[1]);
  expect(chaves[1]).not.toBe(chaves[0]);
  await expect(page).toHaveURL(urlInicial);
  expect(navegacoes).toEqual([]);
});

test('proxy recusa escrita de origem externa, método indevido e Host externo', async ({
  request,
}) => {
  const corpo = {
    ticker: 'ALFA3',
    lado: 'COMPRA',
    tipo: 'MARKET',
    validade: 'IOC',
    quantidade: 1,
  };
  const url = '/servicos/front-office/api/v1/ordens';
  const externa = await request.post(url, {
    data: corpo,
    headers: { Origin: 'http://evil.example', 'X-Idempotency-Key': 'e2e-externa-0001' },
  });
  expect(externa.status()).toBe(403);
  const semOrigem = await request.post(url, {
    data: corpo,
    headers: { 'X-Idempotency-Key': 'e2e-semorigem-01' },
  });
  expect(semOrigem.status()).toBe(403);
  expect((await request.delete(url)).status()).toBe(403);
  expect((await request.get(url, { headers: { Host: 'evil.example' } })).status()).toBe(421);
});

test('mostra Stop aguardando gatilho e execução preservando tipo e validade', async ({
  page,
}, testInfo) => {
  await page.goto('/');
  const enviar = async (
    lado: string,
    quantidade: number,
    validade: string,
    precoLimite = '21.80',
  ) => {
    const r = await page.request.post('/servicos/front-office/api/v1/ordens', {
      headers: { Origin: new URL(page.url()).origin, 'X-Idempotency-Key': crypto.randomUUID() },
      data: { ticker: 'BETA3', lado, tipo: 'LIMIT', quantidade, validade, precoLimite },
    });
    expect(r.status()).toBe(202);
    return r.json();
  };
  const esperarEstado = async (id: string, estado: string) => {
    await expect
      .poll(
        async () => {
          const r = await page.request.get(`/servicos/front-office/api/v1/ordens/${id}`);
          expect(r.status()).toBe(200);
          return (await r.json()).estado;
        },
        { timeout: 15000 },
      )
      .toBe(estado);
  };
  // Estabelece último negócio abaixo do gatilho também em execuções repetidas.
  const seed = await enviar('VENDA', 1, 'DIA', '20.00');
  await esperarEstado(seed.id, 'ABERTA');
  const compraSeed = await enviar('COMPRA', 1, 'IOC', '20.00');
  await esperarEstado(compraSeed.id, 'EXECUTADA');
  const boleta = page.getByRole('form', { name: 'Ordem simples' });
  await boleta.getByLabel('Ativo').selectOption('BETA3');
  await boleta.getByLabel('Tipo').selectOption('STOP');
  await boleta.getByLabel('Quantidade').fill('2');
  await boleta.getByLabel('Preço de disparo (R$)').fill('21.80');
  let registrada!: { id: string };
  await page.route('**/servicos/front-office/api/v1/ordens', async (rota) => {
    if (rota.request().method() !== 'POST') return rota.fallback();
    const respostaReal = await rota.fetch();
    // Guarda o ID da resposta real antes de entregá-la ao navegador, como no caso Limit.
    registrada = await respostaReal.json();
    await rota.fulfill({ response: respostaReal });
  });
  const resposta = page.waitForResponse(
    (r) => r.url().endsWith('/api/v1/ordens') && r.request().method() === 'POST',
  );
  await boleta.getByRole('button', { name: 'Registrar ordem' }).click();
  expect((await resposta).status()).toBe(202);
  const consultar = async () => {
    const r = await page.request.get(`/servicos/front-office/api/v1/ordens/${registrada.id}`);
    expect(r.status()).toBe(200);
    return r.json();
  };
  await expect
    .poll(async () => (await consultar()).estado, { timeout: 15000 })
    .toBe('AGUARDANDO_GATILHO');
  await page.getByRole('button', { name: 'Atualizar ordens' }).click();
  const linha = page
    .getByRole('row')
    .filter({ hasText: 'BETA3' })
    .filter({ hasText: 'Stop' })
    .first();
  await expect(linha).toContainText('Aguardando gatilho');
  await page.screenshot({ path: testInfo.outputPath('stop-pendente.png'), fullPage: true });

  const oferta = await enviar('VENDA', 3, 'DIA');
  await expect
    .poll(
      async () => {
        const r = await page.request.get(`/servicos/front-office/api/v1/ordens/${oferta.id}`);
        return (await r.json()).estado;
      },
      { timeout: 15000 },
    )
    .toBe('ABERTA');
  await enviar('COMPRA', 1, 'IOC');
  await expect.poll(async () => (await consultar()).estado, { timeout: 15000 }).toBe('EXECUTADA');
  expect(await consultar()).toMatchObject({
    tipo: 'STOP',
    validade: 'DIA',
    quantidadeExecutada: 2,
  });
  await page.getByRole('button', { name: 'Atualizar ordens' }).click();
  await expect(linha).toContainText('Executada');
  await page.screenshot({ path: testInfo.outputPath('stop-executada.png'), fullPage: true });
});
