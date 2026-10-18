import { expect, test } from '@playwright/test';

test('registra ordem Limit pela boleta, sem credencial no navegador nem envio duplicado', async ({
  page,
}, testInfo) => {
  const erros: string[] = [];
  page.on('pageerror', (erro) => erros.push(erro.message));
  page.on('console', (mensagem) => {
    if (mensagem.type() === 'error') erros.push(mensagem.text());
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
  const resposta = page.waitForResponse(
    (r) => r.url().endsWith('/api/v1/ordens') && r.request().method() === 'POST',
  );
  await boleta.getByRole('button', { name: 'Registrar ordem' }).dblclick();
  expect((await resposta).status()).toBe(202);
  const status = page.getByRole('status');
  await expect(status).toContainText('Ordem registrada — estado Recebida.');
  await expect(status).toContainText('Compra 25 BETA3');
  await expect(page.getByRole('region', { name: 'Minhas ordens' })).toContainText('19,75');
  expect(posts).toHaveLength(1);
  expect(posts[0].autorizacao).toBeUndefined();
  expect(posts[0].chave).toMatch(/^[0-9a-f-]{36}$/);
  expect((await page.content()).toLowerCase()).not.toContain('executada');
  expect(erros).toEqual([]);
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
  await expect(page.getByRole('alert')).toContainText('Preço inválido');

  await page.getByRole('button', { name: 'OCO (Take Profit + Stop Loss)' }).click();
  const oco = page.getByRole('form', { name: 'Ordem OCO' });
  await oco.getByLabel('Alvo — Take Profit (R$)').fill('11.00');
  await oco.getByLabel('Preço de disparo (R$)').fill('9.50');
  await oco.getByRole('button', { name: 'Registrar OCO' }).click();
  await expect(page.getByRole('status')).toContainText('Take Profit: Venda 100 ALFA3');
  await expect(page.getByRole('status')).toContainText('Stop Loss: Venda 100 ALFA3');
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
