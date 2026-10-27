import { expect, test, type APIRequestContext, type Page } from '@playwright/test';

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
  const erros: string[] = [];
  page.on('pageerror', (erro) => erros.push(erro.message));
  page.on('console', (mensagem) => {
    if (mensagem.type() === 'error') erros.push(mensagem.text());
  });
  // ADR-015: leituras do BO respondem 503 problem+json durante lag. Hoje CicloPosicoes invalida a
  // prontidão a cada lote, inclusive o da própria ordem; a prontidão por lag real é pendência da V01.
  const indisponibilidadesBo: string[] = [];
  const leiturasPosicoes: number[] = [];
  page.on('response', (r) => {
    const caminho = new URL(r.url()).pathname;
    if (caminho === '/servicos/back-office/api/v1/posicoes') leiturasPosicoes.push(r.status());
    if (r.status() !== 503) return;
    const leituraBo = /^\/servicos\/back-office\/api\/v1\/(posicoes|negocios|razao)$/.test(caminho);
    const problema = (r.headers()['content-type'] ?? '').startsWith('application/problem+json');
    indisponibilidadesBo.push(leituraBo && problema ? 'contratado' : `inesperado ${caminho}`);
  });
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
  const console503 = 'Failed to load resource: the server responded with a status of 503';
  expect(indisponibilidadesBo.filter((tipo) => tipo !== 'contratado')).toEqual([]);
  expect(erros.filter((erro) => erro.startsWith(console503)).length).toBeLessThanOrEqual(
    indisponibilidadesBo.length,
  );
  expect(erros.filter((erro) => !erro.startsWith(console503))).toEqual([]);
  // Posições consulta a cada 2 s: depois de um 503 contratado, um 200 posterior volta a tela sem alerta.
  await expect.poll(() => leiturasPosicoes.at(-1), { timeout: 10000 }).toBe(200);
  await expect(page.locator('app-posicoes').getByRole('alert')).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await page.getByRole('heading', { name: 'Boleta' }).scrollIntoViewIfNeeded();
  await page.screenshot({ path: testInfo.outputPath('boleta.png'), fullPage: true });
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
