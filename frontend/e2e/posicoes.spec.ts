import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';

test('negócios reais atualizam posição e resultado sem atualizar a página', async ({
  page,
  request,
  context,
}, testInfo) => {
  const ressinc: string[] = [];
  const atualizacoes: string[] = [];
  const autorizacoesWs: boolean[] = [];
  const upgrades: number[] = [];
  const canais = new Set<string>();
  const cdp = await context.newCDPSession(page);
  await cdp.send('Network.enable');
  cdp.on('Network.webSocketCreated', (evento) => {
    if (evento.url.includes('/servicos/front-office/api/v1/atualizacoes'))
      canais.add(evento.requestId);
  });
  cdp.on('Network.webSocketWillSendHandshakeRequest', (evento) => {
    if (canais.has(evento.requestId))
      autorizacoesWs.push(
        Object.keys(evento.request.headers).some((nome) => nome.toLowerCase() === 'authorization'),
      );
  });
  cdp.on('Network.webSocketHandshakeResponseReceived', (evento) => {
    if (canais.has(evento.requestId)) upgrades.push(evento.response.status);
  });
  page.on('websocket', (canal) => {
    if (!canal.url().includes('/servicos/front-office/api/v1/atualizacoes')) return;
    expect(new URL(canal.url()).search).toBe('');
    canal.on('framereceived', ({ payload }) => {
      try {
        const frame = JSON.parse(String(payload));
        if (frame.tipo === 'RESSINCRONIZAR') ressinc.push(frame.revisao);
        if (frame.tipo === 'ATUALIZACAO' && typeof frame.revisao === 'string')
          atualizacoes.push(frame.revisao);
      } catch {
        // Quadros de controle não são avisos de invalidação JSON.
      }
    });
  });
  // Instrumento somente do teste: encerra o transporte real para observar a reconexão da aplicação.
  await page.addInitScript(() => {
    const Original = window.WebSocket;
    const abertas = new Set<WebSocket>();
    window.WebSocket = class extends Original {
      constructor(url: string | URL, protocolos?: string | string[]) {
        super(url, protocolos);
        abertas.add(this);
        this.addEventListener('close', () => abertas.delete(this));
      }
    };
    Object.defineProperty(window, '__prosperoFecharAtualizacoes', {
      value: () => {
        for (const canal of abertas)
          if (canal.url.includes('/api/v1/atualizacoes')) canal.close(1000);
      },
    });
  });
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
  let consultasTrajetoria = 0;
  page.on('request', (r) => {
    if (r.url().includes('/servicos/')) autorizacoes.push(r.headers()['authorization']);
    if (r.url().includes('/trajetoria')) consultasTrajetoria++;
  });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Posições e resultado' })).toBeVisible();
  await expect.poll(() => upgrades.filter((s) => s === 101).length).toBeGreaterThan(0);
  await expect.poll(() => ressinc.length).toBeGreaterThan(0);
  await expect.poll(async () => (await posicao())?.quantidade, { timeout: 20000 }).toBe('0');
  const linha = page
    .getByRole('region', { name: 'Tabela de posições' })
    .locator('tr[data-ticker="DELTA3"]');
  await expect(linha.getByRole('cell').nth(0)).toContainText('Zerada');

  async function negociar(lado: 'COMPRA' | 'VENDA', quantidade = 1) {
    const boleta = page.getByRole('form', { name: 'Ordem simples' });
    await boleta.getByLabel('Ativo').selectOption('DELTA3');
    await boleta.getByLabel('Tipo').selectOption('LIMIT');
    await boleta.getByLabel('Lado').selectOption(lado);
    await boleta.getByLabel('Validade').selectOption('IOC');
    await boleta.getByLabel('Quantidade').fill(String(quantidade));
    await boleta.getByLabel('Preço limite (R$)').fill('40.00');
    const resposta = page.waitForResponse(
      (r) => r.url().endsWith('/api/v1/ordens') && r.request().method() === 'POST',
    );
    await boleta.getByRole('button', { name: 'Registrar ordem', exact: true }).click();
    const recebida = await resposta;
    expect(recebida.status()).toBe(202);
    return recebida.json();
  }
  await repousar('VENDA');
  const antesAtualizacoes = atualizacoes.length;
  const parcial = await negociar('COMPRA', 2);
  await expect.poll(async () => (await posicao())?.quantidade, { timeout: 20000 }).toBe('1');
  await expect(linha.getByRole('cell').nth(0)).toContainText('Comprada');
  await expect(linha.getByRole('cell').nth(1)).toHaveText('40,0000');
  await expect(linha.getByRole('cell').nth(2)).toHaveText('0,0000');
  await expect(linha.getByRole('cell').nth(3)).toHaveText('0,0000');
  await expect
    .poll(
      async () => {
        const resposta = await request.get(`/servicos/front-office/api/v1/ordens/${parcial.id}`);
        if (!resposta.ok()) return null;
        const ordem = await resposta.json();
        return [ordem.quantidadeExecutada, ordem.quantidadeCancelada];
      },
      { timeout: 20000 },
    )
    .toEqual([1, 1]);
  await page
    .getByRole('button', { name: `Ver trajetória da ordem ${parcial.id}`, exact: true })
    .click();
  const trajetoria = page.locator('app-trajetoria');
  for (const origem of ['front-office', 'middle-office', 'matching-engine', 'back-office']) {
    await expect(trajetoria.locator(`li[data-origem="${origem}"]`).first()).toBeVisible({
      timeout: 20000,
    });
  }
  await expect(trajetoria.locator('li[data-tipo="NEGOCIO_CONTABILIZADO"]')).toHaveCount(1);
  const confirmada = await request.get(
    `/servicos/front-office/api/v1/ordens/${parcial.id}/trajetoria`,
  );
  expect(confirmada.status()).toBe(200);
  const revisaoConfirmada = (await confirmada.json()).revisao;
  expect(typeof revisaoConfirmada).toBe('string');
  // Exige publicação WS de uma revisão que já contém o marco BO confirmado, além dos GETs de fallback.
  await expect
    .poll(
      () =>
        atualizacoes.slice(antesAtualizacoes).some((r) => BigInt(r) >= BigInt(revisaoConfirmada)),
      { timeout: 20000 },
    )
    .toBe(true);
  const antesRessinc = ressinc.length;
  const antesConsultas = consultasTrajetoria;
  await page.evaluate(() =>
    (window as unknown as { __prosperoFecharAtualizacoes(): void }).__prosperoFecharAtualizacoes(),
  );
  await expect.poll(() => ressinc.length, { timeout: 15000 }).toBeGreaterThan(antesRessinc);
  await expect.poll(() => consultasTrajetoria, { timeout: 15000 }).toBeGreaterThan(antesConsultas);
  await expect(trajetoria.locator('li[data-tipo="NEGOCIO_CONTABILIZADO"]')).toHaveCount(1);
  await page
    .getByRole('heading', { name: 'Trajetória da ordem', exact: true })
    .scrollIntoViewIfNeeded();
  await page.screenshot({ path: testInfo.outputPath('trajetoria.png'), fullPage: true });
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
  expect(autorizacoesWs.length).toBeGreaterThan(0);
  expect(autorizacoesWs.every((presente) => !presente)).toBe(true);
  await cdp.detach();
});
