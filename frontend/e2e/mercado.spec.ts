import { expect, test } from '@playwright/test';

// Pré-condição: namespace principal preparado por `python3 scripts/mercado.py preparar-demo`.
test('consulta mercado real pelo proxy, sem credencial no navegador', async ({
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
    'Nenhum snapshot preparado',
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
  await page.getByRole('button', { name: 'Atualizar mercado' }).click();
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expect(page.getByRole('table', { name: /Ativos com último preço/ })).toContainText('ALFA3');
});
