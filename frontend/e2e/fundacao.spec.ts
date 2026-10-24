import { expect, test } from '@playwright/test';

test('apresenta a página principal sem erros, sem transbordamento e sem estado dos serviços', async ({
  page,
}, testInfo) => {
  const erros: string[] = [];
  page.on('pageerror', (erro) => erros.push(erro.message));
  page.on('console', (mensagem) => {
    if (mensagem.type() === 'error') erros.push(mensagem.text());
  });

  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Próspero');
  await expect(
    page.getByRole('heading', { name: 'Histórico e liquidação', exact: true }),
  ).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Estado dos serviços' })).toHaveCount(0);
  await expect(
    page.getByRole('heading', { name: 'Uma ordem, quatro responsabilidades.' }),
  ).toBeVisible();
  expect(erros).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await page.screenshot({
    path: testInfo.outputPath('fundacao.png'),
    fullPage: true,
  });
});

test('segue o tema do sistema e persiste a alternância entre recargas', async ({
  page,
}, testInfo) => {
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.goto('/');
  const botao = page.getByRole('button', { name: 'Tema escuro' });
  await expect(botao).toHaveAttribute('aria-pressed', 'true');
  const fundo = () => page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  expect(await fundo()).toBe('rgb(15, 23, 20)');

  await botao.focus();
  await page.keyboard.press('Enter');
  await expect(botao).toHaveAttribute('aria-pressed', 'false');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  expect(await fundo()).toBe('rgb(246, 248, 244)');

  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  await expect(page.getByRole('button', { name: 'Tema escuro' })).toHaveAttribute(
    'aria-pressed',
    'false',
  );

  await page.getByRole('button', { name: 'Tema escuro' }).click();
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  expect(await fundo()).toBe('rgb(15, 23, 20)');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await page.screenshot({ path: testInfo.outputPath('tema-escuro.png'), fullPage: true });
});
