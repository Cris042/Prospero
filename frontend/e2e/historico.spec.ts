import { expect, test } from '@playwright/test';

test('histórico consulta APIs reais, preserva a conta e não envia credenciais pelo navegador', async ({
  page,
  request,
}, testInfo) => {
  const negocioResposta = await request.get('/servicos/back-office/api/v1/negocios?limite=20');
  const razaoResposta = await request.get('/servicos/back-office/api/v1/razao?limite=20');
  expect(negocioResposta.status()).toBe(200);
  expect(razaoResposta.status()).toBe(200);
  expect(negocioResposta.headers()['cache-control']).toBe('no-store');
  expect(razaoResposta.headers()['cache-control']).toBe('no-store');
  const negocios = await negocioResposta.json();
  const razao = await razaoResposta.json();
  expect(negocios.conta).toBe('CONTA-001');
  expect(razao.conta).toBe('CONTA-001');
  expect(negocios.negocios.length).toBeGreaterThan(0);
  expect(razao.lancamentos.length).toBeGreaterThan(0);
  const liquidado = process.env['PROSPERO_HISTORICO_LIQUIDADO'] === 'true';
  expect(
    negocios.negocios.every(
      (negocio: { liquidacao: string }) =>
        negocio.liquidacao === (liquidado ? 'LIQUIDADO' : 'PENDENTE'),
    ),
  ).toBe(true);
  if (liquidado)
    expect(razao.lancamentos.some((linha: { momento: string }) => linha.momento === 'D2')).toBe(
      true,
    );
  for (const negocio of negocios.negocios) {
    expect(negocio).not.toHaveProperty('comprador');
    expect(negocio).not.toHaveProperty('vendedor');
    expect(negocio).not.toHaveProperty('contraparte');
  }
  const autorizacoes: (string | undefined)[] = [];
  page.on('request', (pedido) => {
    if (/\/servicos\/back-office\/api\/v1\/(negocios|razao)/.test(pedido.url()))
      autorizacoes.push(pedido.headers()['authorization']);
  });
  await page.goto('/');
  const historico = page.locator('app-historico');
  await expect(historico.getByRole('heading', { name: 'Histórico e liquidação' })).toBeVisible();
  const tabela = historico.getByRole('region', { name: 'Tabela de negócios' });
  await expect(tabela.locator('tbody tr').first()).toContainText(negocios.negocios[0].negocio);
  await expect(tabela.locator('tbody tr').first()).toContainText(
    liquidado ? 'Liquidado' : 'A liquidar',
  );
  await expect(historico.getByRole('region', { name: 'Tabela da razão' })).toBeVisible();
  await expect(historico.getByRole('button', { name: 'Atualizar histórico' })).toBeEnabled();
  await historico.getByRole('button', { name: 'Atualizar histórico' }).click();
  await expect(historico.getByRole('button', { name: 'Atualizar histórico' })).toBeEnabled();
  expect(autorizacoes.length).toBeGreaterThanOrEqual(4);
  expect(autorizacoes.every((valor) => valor === undefined)).toBe(true);
  await historico.scrollIntoViewIfNeeded();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({
    path: testInfo.outputPath(`historico-${liquidado ? 'liquidado' : 'pendente'}.png`),
    fullPage: true,
  });
});

test('histórico mantém centavos exatos e informa consulta desatualizada após falha', async ({
  page,
}) => {
  let indisponivel = false;
  // Este caso isola a apresentação. O teste anterior cobre backend e proxy reais.
  await page.route('**/servicos/back-office/api/v1/negocios?*', async (route) => {
    await route.fulfill({
      status: indisponivel ? 503 : 200,
      json: indisponivel
        ? { codigo: 'QUARENTENA' }
        : {
            conta: 'CONTA-001',
            proximoCursor: null,
            negocios: [
              {
                negocio: 'ALFA3-2026-10-23-1',
                ticker: 'ALFA3',
                lado: 'COMPRADOR',
                quantidade: '1',
                valor: '9007199254740993.01',
                dataSessao: '2026-10-23',
                dataLiquidacao: '2026-10-27',
                liquidacao: 'LIQUIDADO',
              },
            ],
          },
    });
  });
  await page.route('**/servicos/back-office/api/v1/razao?*', async (route) => {
    await route.fulfill({
      status: 200,
      json: { conta: 'CONTA-001', lancamentos: [], proximoCursor: null },
    });
  });
  await page.goto('/');
  const historico = page.locator('app-historico');
  await expect(historico).toContainText('9.007.199.254.740.993,01');
  await expect(historico).toContainText('Liquidado');
  await expect(historico.getByRole('button', { name: 'Mais negócios' })).toBeDisabled();
  indisponivel = true;
  await historico.getByRole('button', { name: 'Atualizar histórico' }).click();
  await expect(historico.getByRole('alert')).toContainText('última consulta confirmada');
  await expect(historico).toContainText('9.007.199.254.740.993,01');
});
