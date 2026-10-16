import { expect, test, type Page } from '@playwright/test';
import { resolve } from 'node:path';

const servicos = [
  { id: 'front-office', nome: 'Front office' },
  { id: 'middle-office', nome: 'Middle office' },
  { id: 'matching-engine', nome: 'Matching engine' },
  { id: 'back-office', nome: 'Back office' },
];

function cartao(page: Page, nome: string) {
  return page
    .getByRole('article')
    .filter({ has: page.getByRole('heading', { name: nome, exact: true }) });
}

async function esperarDisponiveis(page: Page) {
  await expect(page.getByRole('article')).toHaveCount(4);
  for (const servico of servicos) {
    await expect(cartao(page, servico.nome).locator('.estado')).toHaveText('● Disponível', {
      timeout: 15_000,
    });
    await expect(
      cartao(page, servico.nome).getByRole('link', { name: /Explorar a API/ }),
    ).toHaveAttribute('rel', 'noopener noreferrer');
  }
  await expect(page.getByRole('button', { name: 'Atualizar', exact: true })).toBeEnabled();
}

test('consulta os quatro serviços reais e atualiza sem erros ou transbordamento', async ({
  page,
}, testInfo) => {
  const erros: string[] = [];
  page.on('pageerror', (erro) => erros.push(erro.message));
  page.on('console', (mensagem) => {
    if (mensagem.type() === 'error') erros.push(mensagem.text());
  });
  const respostas: { url: string; status: number }[] = [];
  page.on('response', (resposta) => {
    if (
      /\/servicos\/[^/]+\/(api\/v1\/sistema|q\/health\/ready)$/.test(
        new URL(resposta.url()).pathname,
      )
    ) {
      respostas.push({ url: resposta.url(), status: resposta.status() });
    }
  });

  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Próspero');
  await expect(
    page.getByText('Negociação ainda não implementada.', { exact: false }),
  ).toBeVisible();
  await esperarDisponiveis(page);
  for (const servico of servicos) {
    for (const rota of ['api/v1/sistema', 'q/health/ready']) {
      expect(
        respostas.filter((resposta) => resposta.url.endsWith(`/servicos/${servico.id}/${rota}`)),
      ).toHaveLength(1);
    }
  }
  expect(respostas.every((resposta) => resposta.status === 200)).toBe(true);

  const atualizacoes = servicos.flatMap((servico) =>
    ['api/v1/sistema', 'q/health/ready'].map((rota) =>
      page.waitForResponse(
        (resposta) =>
          resposta.url().endsWith(`/servicos/${servico.id}/${rota}`) && resposta.status() === 200,
      ),
    ),
  );
  await page.getByRole('button', { name: 'Atualizar', exact: true }).click();
  await Promise.all(atualizacoes);
  await esperarDisponiveis(page);
  expect(respostas).toHaveLength(16);
  expect(erros).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await page.screenshot({
    path: resolve('..', 'docs', 'evidencias', `P02-${testInfo.project.name}.png`),
    fullPage: true,
  });
});

test('isola indisponibilidade de um backend e recupera após nova consulta', async ({ page }) => {
  const erros: string[] = [];
  page.on('pageerror', (erro) => erros.push(erro.message));
  const rota = '**/servicos/middle-office/api/v1/sistema';
  await page.route(rota, (route) =>
    route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: '{"erro":"indisponibilidade controlada no teste"}',
    }),
  );
  await page.goto('/');
  await expect(cartao(page, 'Middle office').locator('.estado')).toHaveText('● Indisponível');
  await expect(cartao(page, 'Middle office')).toContainText('Não foi possível consultar');
  for (const servico of servicos.filter((servico) => servico.id !== 'middle-office')) {
    await expect(cartao(page, servico.nome).locator('.estado')).toHaveText('● Disponível', {
      timeout: 15_000,
    });
  }
  await page.unroute(rota);
  await page.getByRole('button', { name: 'Atualizar', exact: true }).click();
  await esperarDisponiveis(page);
  expect(erros).toEqual([]);
});
