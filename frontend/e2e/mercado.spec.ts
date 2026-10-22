import { expect, test } from '@playwright/test';

// Pré-condição: fixture UUID preparada por `python3 scripts/mercado.py preparar-demo`.
const NAMESPACE_DEMO = '8d3df0f3-76d3-4ac0-b9a8-26f040e5721c';
test('consulta fixture sintética por HTTP real pelo proxy, sem credencial no navegador', async ({
  page,
}, testInfo) => {
  const erros: string[] = [];
  page.on('pageerror', (erro) => erros.push(erro.message));
  page.on('console', (mensagem) => {
    if (mensagem.type() === 'error') erros.push(mensagem.text());
  });
  const autorizacoes: (string | undefined)[] = [];
  page.on('request', (pedido) => {
    if (pedido.url().includes('/api/v1/ativos'))
      autorizacoes.push(pedido.headers()['authorization']);
  });

  // Mantém backend e proxy reais; acrescenta apenas o namespace da fixture histórica.
  await page.route('**/servicos/front-office/api/v1/ativos**', async (route) => {
    const url = new URL(route.request().url());
    if (
      route.request().method() === 'GET' &&
      /^\/servicos\/front-office\/api\/v1\/ativos(?:\/[A-Z0-9]+\/livro)?$/.test(url.pathname)
    ) {
      url.searchParams.set('namespace', NAMESPACE_DEMO);
      await route.continue({ url: url.toString() });
      return;
    }
    await route.continue();
  });
  await page.goto('/');
  const tabela = page.getByRole('table', { name: /Ativos com último preço/ });
  await expect(tabela.getByRole('row')).toHaveCount(6);
  const alfa = tabela.getByRole('row', { name: /ALFA3/ });
  await expect(alfa).toContainText('10,35');
  await expect(alfa).toContainText('+3,50%');
  await expect(alfa).toContainText('4.125,00');
  await expect(tabela.getByRole('row', { name: /OMEGA3/ })).toContainText('—');

  const livro = page.getByRole('region', { name: 'Livro de ALFA3' });
  await expect(livro).toContainText('Dados sintéticos');
  const compra = livro.getByRole('table', { name: /Compra/ });
  await expect(compra).toContainText('6 nível(is)');
  await expect(compra.getByRole('row')).toHaveCount(6);
  await expect(compra.getByRole('row').nth(1)).toContainText('10,30');

  await tabela.getByRole('button', { name: /GAMA3/ }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('region', { name: 'Livro de GAMA3' })).toContainText(
    'Somente vendas',
  );
  await tabela.getByRole('button', { name: /OMEGA3/ }).click();
  await expect(page.getByRole('region', { name: 'Livro de OMEGA3' })).toContainText(
    'Nenhum dado de mercado',
  );

  expect(autorizacoes.length).toBeGreaterThan(0);
  expect(autorizacoes.every((valor) => valor === undefined)).toBe(true);
  expect(erros).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await tabela.getByRole('button', { name: /ALFA3/ }).click();
  await expect(page.getByRole('region', { name: 'Livro de ALFA3' })).toContainText('10,30');
  await page.screenshot({ path: testInfo.outputPath('mercado.png'), fullPage: true });
});

// cotacao.xlsx gera negócios antes do navegador; outros E2Es podem negociar BETA3 depois.
// Consulta o principal e confere seus acumuladores, sem depender da ordem dos E2Es.
test('principal consulta cotação MATCHING pelo proxy', async ({ request }) => {
  await expect
    .poll(
      async () => {
        const resposta = await request.get('/servicos/front-office/api/v1/ativos');
        expect(resposta.status()).toBe(200);
        const ativos = await resposta.json();
        const beta = ativos.find((ativo: { ticker: string }) => ativo.ticker === 'BETA3');
        const volumePositivo =
          Number.isSafeInteger(beta?.volumeQuantidade) && beta.volumeQuantidade > 0;
        const financeiroValido =
          typeof beta?.volumeFinanceiro === 'string' && /^\d+\.\d{2}$/.test(beta.volumeFinanceiro);
        let vwapCalculado: string | null = null;
        if (volumePositivo && financeiroValido) {
          // Σp·q em centavos → preço com quatro casas; divisão inteira HALF_EVEN.
          const numerador = BigInt(beta.volumeFinanceiro.replace('.', '')) * 100n;
          const divisor = BigInt(beta.volumeQuantidade);
          let quociente = numerador / divisor;
          const dobroResto = (numerador % divisor) * 2n;
          if (dobroResto > divisor || (dobroResto === divisor && quociente % 2n !== 0n)) {
            quociente += 1n;
          }
          vwapCalculado = `${quociente / 10000n}.${(quociente % 10000n).toString().padStart(4, '0')}`;
        }
        return {
          origem: beta?.origem,
          ultimoValido:
            typeof beta?.ultimoPreco === 'string' &&
            /^\d+\.\d{2}$/.test(beta.ultimoPreco) &&
            BigInt(beta.ultimoPreco.replace('.', '')) > 0n,
          vwapConsistente:
            typeof beta?.vwap === 'string' &&
            /^\d+\.\d{4}$/.test(beta.vwap) &&
            beta.vwap === vwapCalculado,
          volumePositivo,
          revisaoPositiva: Number.isSafeInteger(beta?.revisao) && beta.revisao > 0,
        };
      },
      { timeout: 15000, intervals: [200, 500, 1000] },
    )
    .toEqual({
      origem: 'MATCHING',
      ultimoValido: true,
      vwapConsistente: true,
      volumePositivo: true,
      revisaoPositiva: true,
    });
  const livro = await request.get('/servicos/front-office/api/v1/ativos/BETA3/livro');
  expect(livro.status()).toBe(200);
  const dados = await livro.json();
  expect(dados.origem).toBe('MATCHING');
  expect(dados.sessao).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  expect(Number.isSafeInteger(dados.revisao)).toBe(true);
  expect(dados.revisao).toBeGreaterThan(0);
});

test('preparação não é alcançável pelo proxy do navegador', async ({ request }) => {
  const resposta = await request.put(
    '/servicos/front-office/api/v1/preparacoes-mercado/00000000-0000-0000-0000-000000000000/ativos/ALFA3',
    { data: { sessao: '2026-10-16', ofertas: [], negocios: [] } },
  );
  expect(resposta.status()).toBe(403);
});

test('preparação de carteiras do MO não é alcançável pelo proxy do navegador', async ({
  request,
}) => {
  for (const caminho of [
    '/servicos/middle-office/api/v1/preparacoes-risco/contas/CONTA-002',
    '/servicos/middle-office/api/v1/preparacoes%2Drisco/contas/CONTA-002',
  ]) {
    const resposta = await request.put(caminho, {
      data: { saldo: '1.00' },
      headers: { Authorization: 'Bearer qualquer' },
    });
    expect(resposta.status()).toBe(403);
  }
  // O proxy não repassa o token do cliente: a carteira exige autenticação no MO.
  const carteira = await request.get('/servicos/middle-office/api/v1/risco/carteira', {
    headers: { Authorization: 'Bearer qualquer' },
  });
  expect(carteira.status()).toBe(401);
});

test('informa indisponibilidade do mercado e recupera', async ({ page }) => {
  const rota = '**/servicos/front-office/api/v1/ativos';
  await page.route(rota, (route) => route.fulfill({ status: 503, body: '{}' }));
  await page.goto('/');
  await expect(page.getByRole('alert')).toContainText('Não foi possível consultar o mercado');
  await page.unroute(rota);
  // A recuperação deve ocorrer pelo próximo ciclo, sem intervenção manual.
  await expect(page.getByRole('alert')).toHaveCount(0, { timeout: 12000 });
  await expect(page.getByRole('table', { name: /Ativos com último preço/ })).toContainText('ALFA3');
});

test('oferta e negócio reais atualizam livro e VWAP sem atualizar manualmente', async ({
  page,
}, testInfo) => {
  test.setTimeout(60000);
  const base = '/servicos/front-office/api/v1';
  const ticker = 'OMEGA3';
  const consultar = async () => {
    const r = await page.request.get(`${base}/ativos`);
    expect(r.status()).toBe(200);
    return (await r.json()).find((a: { ticker: string }) => a.ticker === ticker);
  };
  const ordem = async (id: string) => {
    const r = await page.request.get(`${base}/ordens/${id}`);
    expect(r.status()).toBe(200);
    return r.json();
  };
  const enviar = async (
    lado: string,
    quantidade: number,
    validade: string,
    precoLimite: string,
  ) => {
    const r = await page.request.post(`${base}/ordens`, {
      headers: { Origin: new URL(page.url()).origin, 'X-Idempotency-Key': crypto.randomUUID() },
      data: { ticker, lado, tipo: 'LIMIT', validade, quantidade, precoLimite },
    });
    expect(r.status()).toBe(202);
    return r.json();
  };
  const textual = (valor: string) => {
    const [inteiro, fracao] = valor.split('.');
    return inteiro.replace(/\B(?=(\d{3})+(?!\d))/g, '.') + ',' + fracao;
  };
  await page.goto('/');
  const tabela = page.getByRole('table', { name: /Ativos com último preço/ });
  await expect(tabela.getByRole('row', { name: /OMEGA3/ })).toBeVisible();
  await tabela.getByRole('button', { name: /OMEGA3/ }).click();
  const livro = page.getByRole('region', { name: 'Livro de OMEGA3' });
  const antes = await consultar();
  const quantidade = antes.lote;
  expect(Number.isSafeInteger(quantidade) && quantidade > 0).toBe(true);
  // A validação anterior pode deixar compra DIA em OMEGA3: nossa venda precisa repousar acima dela.
  const centavos = (valor: string) => BigInt(valor.replace('.', ''));
  const referencia = centavos(antes.ultimoPreco ?? antes.precoReferencia);
  const tick = centavos(antes.tick);
  const acimaCompra =
    antes.melhorCompra === null ? referencia : centavos(antes.melhorCompra) + tick;
  const precoCentavos = acimaCompra > referencia ? acimaCompra : referencia;
  expect(precoCentavos * 100n).toBeGreaterThanOrEqual(referencia * 90n);
  expect(precoCentavos * 100n).toBeLessThanOrEqual(referencia * 110n);
  expect(precoCentavos % tick).toBe(0n);
  const preco = `${precoCentavos / 100n}.${(precoCentavos % 100n).toString().padStart(2, '0')}`;
  const venda = await enviar('VENDA', quantidade, 'DIA', preco);
  try {
    await expect
      .poll(async () => (await ordem(venda.id)).estado, { timeout: 15000 })
      .toBe('ABERTA');
    await expect(livro.getByRole('table', { name: /Venda/ })).toContainText(textual(preco), {
      timeout: 15000,
    });
    // A oferta muda o livro, mas não o volume ou VWAP antes da execução.
    expect((await consultar()).volumeQuantidade).toBe(antes.volumeQuantidade);
    const compra = await enviar('COMPRA', quantidade, 'IOC', preco);
    await expect
      .poll(async () => (await ordem(compra.id)).estado, { timeout: 15000 })
      .toBe('EXECUTADA');
    await expect
      .poll(async () => (await consultar()).volumeQuantidade, { timeout: 15000 })
      .toBe((antes.volumeQuantidade ?? 0) + quantidade);
    const depois = await consultar();
    const linha = tabela.getByRole('row', { name: /OMEGA3/ });
    await expect(linha.getByRole('cell').nth(5)).toHaveText(
      String(depois.volumeQuantidade).replace(/\B(?=(\d{3})+(?!\d))/g, '.'),
      { timeout: 15000 },
    );
    await expect(linha.getByRole('cell').nth(7)).toHaveText(textual(depois.vwap), {
      timeout: 15000,
    });
    await expect(livro.getByRole('table', { name: /Venda/ })).toContainText('Sem ofertas.', {
      timeout: 15000,
    });
    await expect(livro).toContainText('Mercado real');
    await page.screenshot({ path: testInfo.outputPath('mercado-rf06b-real.png'), fullPage: true });
  } finally {
    // RF10 não oferece cancelamento ainda: eventual saldo da oferta é consumido por negócio real.
    const atual = await ordem(venda.id);
    const restante = atual.quantidade - atual.quantidadeExecutada - atual.quantidadeCancelada;
    if (restante > 0 && ['ABERTA', 'PARCIALMENTE_EXECUTADA'].includes(atual.estado)) {
      const limpeza = await enviar('COMPRA', restante, 'IOC', preco);
      await expect
        .poll(async () => (await ordem(limpeza.id)).estado, { timeout: 15000 })
        .toBe('EXECUTADA');
    }
  }
});

test('consulta de ativos recusa escrita e Host externo sem armazenar respostas', async ({
  request,
}) => {
  const url = '/servicos/front-office/api/v1/ativos';
  expect((await request.post(url, { data: {} })).status()).toBe(403);
  expect((await request.get(url, { headers: { Host: 'evil.example' } })).status()).toBe(421);
  const consulta = await request.get(url);
  expect(consulta.status()).toBe(200);
  expect(consulta.headers()['cache-control']).toContain('no-store');
});

test('limite de consultas responde 429 e se recupera após drenar o burst', async ({ request }) => {
  const url = '/servicos/front-office/api/v1/ativos';
  const respostas = await Promise.all(Array.from({ length: 25 }, () => request.get(url)));
  const limitadas = respostas.filter((r) => r.status() === 429);
  expect(limitadas.length).toBeGreaterThan(0);
  for (const resposta of limitadas)
    expect(resposta.headers()['cache-control']).toContain('no-store');
  // Uma sequência sustentada abaixo de 5r/s mostra recuperação do orçamento antes do próximo teste.
  let sequencia = 0;
  await expect
    .poll(
      async () => {
        const r = await request.get(url);
        sequencia = r.status() === 200 ? sequencia + 1 : 0;
        return sequencia;
      },
      { timeout: 15000, intervals: [400] },
    )
    .toBe(10);
});
