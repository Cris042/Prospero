import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';

test('negócios reais atualizam posição e resultado sem atualizar a página', async ({
  page,
  request,
}, testInfo) => {
  const arquivo = process.env['PROSPERO_POSICOES_E2E_CONFIG'];
  if (!arquivo)
    throw new Error(
      'Configure PROSPERO_POSICOES_E2E_CONFIG com a configuração privada do perfil posicoes.',
    );
  const config = JSON.parse(readFileSync(arquivo, 'utf8'));
  // A prova de risco deixa a CONTA-003 sem custódia; a CONTA-002 conserva DELTA3 disponível.
  const contraparte = config.servicos['ordens-a'];
  const origem = new URL(contraparte.origem);
  expect(origem.protocol).toBe('http:');
  expect(['localhost', '127.0.0.1']).toContain(origem.hostname);

  // Credencial da contraparte pertence somente ao processo Node, nunca ao navegador ou aos artefatos PNG.
  async function api(caminho: string, corpo?: object) {
    let resposta: Response;
    try {
      resposta = await fetch(new URL(caminho, origem), {
        method: corpo ? 'POST' : 'GET',
        headers: {
          Authorization: contraparte.headers.Authorization,
          ...(corpo
            ? { 'Content-Type': 'application/json', 'X-Idempotency-Key': `pnl-${randomUUID()}` }
            : {}),
        },
        body: corpo ? JSON.stringify(corpo) : undefined,
        redirect: 'error',
        signal: AbortSignal.timeout(8000),
      });
    } catch {
      throw new Error('API local da contraparte indisponível.');
    }
    expect(resposta.status).toBe(corpo ? 202 : 200);
    return resposta.json();
  }
  async function repousar(lado: 'COMPRA' | 'VENDA') {
    const registro = await api('/api/v1/ordens', {
      ticker: 'DELTA3',
      lado,
      tipo: 'LIMIT',
      validade: 'DIA',
      quantidade: 1,
      precoLimite: '40.00',
    });
    await expect
      .poll(async () => (await api(`/api/v1/ordens/${encodeURIComponent(registro.id)}`)).estado, {
        timeout: 15000,
      })
      .toBe('ABERTA');
  }
  async function posicao() {
    const resposta = await request.get('/servicos/back-office/api/v1/posicoes');
    if (resposta.status() !== 200) return null;
    expect(resposta.headers()['cache-control']).toBe('no-store');
    const corpo = await resposta.json();
    expect(corpo.conta).toBe('CONTA-001');
    return corpo.posicoes.find((p: { ticker: string }) => p.ticker === 'DELTA3');
  }
  const autorizacoes: (string | undefined)[] = [];
  page.on('request', (r) => {
    if (r.url().includes('/servicos/back-office/api/v1/posicoes'))
      autorizacoes.push(r.headers()['authorization']);
  });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Posições e resultado' })).toBeVisible();
  await expect.poll(async () => (await posicao())?.quantidade, { timeout: 20000 }).toBe('0');
  const linha = page
    .getByRole('region', { name: 'Tabela de posições' })
    .locator('tr[data-ticker="DELTA3"]');
  await expect(linha.getByRole('cell').nth(0)).toContainText('Zerada');

  async function negociar(lado: 'COMPRA' | 'VENDA') {
    const boleta = page.getByRole('form', { name: 'Ordem simples' });
    await boleta.getByLabel('Ativo').selectOption('DELTA3');
    await boleta.getByLabel('Tipo').selectOption('LIMIT');
    await boleta.getByLabel('Lado').selectOption(lado);
    await boleta.getByLabel('Validade').selectOption('IOC');
    await boleta.getByLabel('Quantidade').fill('1');
    await boleta.getByLabel('Preço limite (R$)').fill('40.00');
    const resposta = page.waitForResponse(
      (r) => r.url().endsWith('/api/v1/ordens') && r.request().method() === 'POST',
    );
    await boleta.getByRole('button', { name: 'Registrar ordem', exact: true }).click();
    expect((await resposta).status()).toBe(202);
  }
  await repousar('VENDA');
  await negociar('COMPRA');
  await expect.poll(async () => (await posicao())?.quantidade, { timeout: 20000 }).toBe('1');
  await expect(linha.getByRole('cell').nth(0)).toContainText('Comprada');
  await expect(linha.getByRole('cell').nth(1)).toHaveText('40,0000');
  await expect(linha.getByRole('cell').nth(2)).toHaveText('0,0000');
  await expect(linha.getByRole('cell').nth(3)).toHaveText('0,0000');
  await page.getByRole('heading', { name: 'Posições e resultado' }).scrollIntoViewIfNeeded();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath('posicoes.png'), fullPage: true });
  await repousar('COMPRA');
  await negociar('VENDA');
  await expect.poll(async () => (await posicao())?.quantidade, { timeout: 20000 }).toBe('0');
  await expect(linha.getByRole('cell').nth(0)).toContainText('Zerada');
  await expect(linha.getByRole('cell').nth(1)).toHaveText('—');
  expect(autorizacoes.length).toBeGreaterThan(0);
  expect(autorizacoes.every((a) => a === undefined)).toBe(true);
});
