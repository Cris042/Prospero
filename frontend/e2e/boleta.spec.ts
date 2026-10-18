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
  const respostaRegistro = await resposta;
  expect(respostaRegistro.status()).toBe(202);
  const registrada = await respostaRegistro.json();
  const status = page.getByRole('status');
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
  await boleta.getByLabel('Preço limite (R$)').fill('12,00');
  await boleta.getByRole('button', { name: 'Registrar ordem' }).click();
  await expect(page.getByRole('alert')).toContainText('Faixa aceita: 9,00 a 11,00.');

  await page.getByRole('button', { name: 'OCO (Take Profit + Stop Loss)' }).click();
  const oco = page.getByRole('form', { name: 'Ordem OCO' });
  await oco.getByLabel('Alvo — Take Profit (R$)').fill('11.00');
  await oco.getByLabel('Preço de disparo (R$)').fill('9.50');
  await oco.getByRole('button', { name: 'Registrar OCO' }).click();
  await expect(page.getByRole('status')).toContainText('Take Profit: Venda 100 ALFA3');
  await expect(page.getByRole('status')).toContainText('Stop Loss: Venda 100 ALFA3');
});

test('envia Market e Stop Limit e repete com a mesma chave após falha de rede', async ({
  page,
}) => {
  const chaves: string[] = [];
  page.on('request', (pedido) => {
    if (pedido.method() === 'POST' && pedido.url().endsWith('/api/v1/ordens')) {
      chaves.push(pedido.headers()['x-idempotency-key']);
    }
  });
  await page.goto('/');
  const boleta = page.getByRole('form', { name: 'Ordem simples' });
  await boleta.getByLabel('Tipo').selectOption('MARKET');
  await expect(boleta.getByLabel('Validade').locator('option')).toHaveText([
    'IOC (executa ou cancela)',
    'FOK (tudo ou nada)',
  ]);
  await expect(boleta.getByLabel('Preço limite (R$)')).toHaveCount(0);
  await boleta.getByRole('button', { name: 'Registrar ordem' }).click();
  await expect(page.getByRole('status')).toContainText('Mercado');

  await boleta.getByLabel('Tipo').selectOption('STOP_LIMIT');
  await expect(boleta.getByLabel('Validade').locator('option')).toHaveText(['Dia']);
  await boleta.getByLabel('Preço limite (R$)').fill('9,55');
  await boleta.getByLabel('Preço de disparo (R$)').fill('9,50');
  let falhou = false;
  await page.route('**/servicos/front-office/api/v1/ordens', (rota) => {
    if (rota.request().method() === 'POST' && !falhou) {
      falhou = true;
      return rota.abort('connectionreset');
    }
    return rota.fallback();
  });
  await boleta.getByRole('button', { name: 'Registrar ordem' }).click();
  await expect(page.getByRole('alert')).toContainText('o reenvio não duplica a ordem');
  await boleta.getByRole('button', { name: 'Registrar ordem' }).click();
  await expect(page.getByRole('status')).toContainText('Stop Limit');
  expect(chaves).toHaveLength(3);
  expect(chaves[2]).toBe(chaves[1]);
  expect(chaves[1]).not.toBe(chaves[0]);
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
