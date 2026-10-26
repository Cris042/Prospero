import { expect, test } from '@playwright/test';
import { readFile } from 'node:fs/promises';

// A sessão administrativa é preparada por fetch Node; nunca gravar login/cookies em trace.
test.use({ trace: 'off', viewport: { width: 1440, height: 1600 } });

const dashboards = [
  {
    uid: 'prospero-ordens',
    titulo: 'Próspero — Fluxo de ordens',
    paineis: ['Recebimentos por resultado e tipo', 'Avisos WebSocket enviados'],
  },
  {
    uid: 'prospero-risco',
    titulo: 'Próspero — Risco e reservas',
    paineis: ['Decisões por resultado e motivo', 'Recusas de liquidação persistidas por motivo'],
  },
  {
    uid: 'prospero-matching',
    titulo: 'Próspero — Matching e mercado',
    paineis: ['Prontidão do escritor ME', 'Quarentenas de cotação acumuladas no processo'],
  },
  {
    uid: 'prospero-back-office',
    titulo: 'Próspero — Back office e liquidação',
    paineis: ['Projeção de posições BO em dia', 'Recusas persistidas de crédito no MO'],
  },
];

for (const dashboard of dashboards) {
  test(`Grafana renderiza ${dashboard.uid} e captura os painéis`, async ({
    page,
    context,
  }, info) => {
    test.skip(info.project.name !== 'desktop', 'Capturas Grafana usam o projeto desktop.');
    const arquivo = process.env['PROSPERO_GRAFANA_E2E_CONFIG'];
    test.skip(
      !arquivo && !process.env['CI'],
      'Configuração privada do smoke Grafana não fornecida.',
    );
    if (!arquivo) throw new Error('Configuração privada do smoke Grafana ausente na CI.');
    let config: { origem: string; usuario: string; senha: string };
    try {
      config = JSON.parse(await readFile(arquivo, 'utf8'));
    } catch {
      throw new Error('Não foi possível ler a configuração privada do smoke Grafana.');
    }
    const origem = new URL(config.origem);
    if (
      origem.protocol !== 'http:' ||
      origem.hostname !== '127.0.0.1' ||
      !origem.port ||
      origem.username ||
      origem.password ||
      origem.search ||
      origem.hash ||
      origem.pathname !== '/' ||
      config.usuario !== 'admin' ||
      typeof config.senha !== 'string' ||
      !config.senha
    ) {
      throw new Error('Configuração privada do smoke Grafana inválida.');
    }
    const login = await fetch(new URL('/login', origem), {
      method: 'POST',
      redirect: 'error',
      signal: AbortSignal.timeout(5000),
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ user: config.usuario, password: config.senha }),
    });
    if (!login.ok) throw new Error('Login Grafana recusado.');
    const sessao = login.headers
      .getSetCookie()
      .map((header) => header.split(';', 1)[0])
      .find((cookie) => cookie.startsWith('grafana_session='));
    await login.body?.cancel();
    if (!sessao) throw new Error('Sessão Grafana ausente.');
    await context.addCookies([
      {
        name: 'grafana_session',
        value: sessao.slice('grafana_session='.length),
        url: origem.origin,
        httpOnly: true,
        sameSite: 'Lax',
      },
    ]);

    let respostas = 0;
    const erros: string[] = [];
    const consultas: Promise<void>[] = [];
    page.on('response', (resposta) => {
      if (!resposta.url().includes('/api/ds/query')) return;
      consultas.push(
        (async () => {
          if (!resposta.ok()) {
            erros.push('Consulta Grafana recusada.');
            return;
          }
          const corpo = (await resposta.json()) as {
            results?: Record<string, { error?: unknown; status?: number }>;
          };
          if (
            !corpo.results ||
            Object.values(corpo.results).some((r) => r.error || (r.status ?? 200) >= 400)
          ) {
            erros.push('Consulta Grafana retornou erro.');
          }
          respostas++;
        })().catch(() => {
          erros.push('Resposta Grafana inválida.');
        }),
      );
    });
    await page.goto(new URL(`/d/${dashboard.uid}?from=now-15m&to=now&refresh=`, origem).href);
    await expect(page.getByText(dashboard.titulo, { exact: true }).first()).toBeVisible();
    for (const titulo of dashboard.paineis) {
      const painel = page.getByText(titulo, { exact: true }).first();
      await painel.scrollIntoViewIfNeeded();
      await expect(painel).toBeVisible();
    }
    await expect.poll(() => respostas, { timeout: 15_000 }).toBeGreaterThan(0);
    await page.waitForLoadState('networkidle');
    await Promise.all(consultas);
    expect(erros).toEqual([]);
    await expect(page.getByText(/Query error|An unexpected error happened/i)).toHaveCount(0);
    await page.screenshot({ path: info.outputPath(`${dashboard.uid}.png`), fullPage: true });
  });
}
