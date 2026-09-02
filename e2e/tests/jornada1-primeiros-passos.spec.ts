import { test, expect, type Page } from '@playwright/test';
import { readDemoCredentials } from './demo-credentials';

/**
 * S7: harness Playwright contra a stack real do Compose (F7), sem bypass de seguranca. Estas
 * asercoes sao os primeiros passos da jornada 1 (AC19, AC20, AC22 parcial) -- a jornada so
 * fecha em #0005, entao nenhuma etapa alem de login/carteira/logout e exercitada aqui.
 */

const credentials = readDemoCredentials();

async function logInAs(page: Page, login: string, password: string): Promise<void> {
  await page.goto('/');
  await page.getByRole('button', { name: 'Entrar' }).click();
  await page.locator('#username').fill(login);
  await page.locator('#password').fill(password);
  await page.locator('button[type=submit]').click();
  await expect(page.getByText(login, { exact: true })).toBeVisible();
}

test.describe('Jornada 1 -- primeiros passos', () => {
  test('AC19: login real deixa apenas o cookie de sessao do bff-gerente, nenhum token acessivel ao Angular', async ({
    page,
    context,
  }) => {
    const respostasComToken: string[] = [];
    page.on('response', (resposta) => {
      if (!resposta.url().includes('/bff/')) return;
      resposta
        .text()
        .then((corpo) => {
          if (/access_token|refresh_token/i.test(corpo)) {
            respostasComToken.push(resposta.url());
          }
        })
        .catch(() => {
          // corpo binario ou ja consumido -- nao e onde um token JSON apareceria
        });
    });

    await logInAs(page, credentials['gerente.a'].login, credentials['gerente.a'].senha);

    expect(respostasComToken).toEqual([]);

    const cookies = await context.cookies();
    expect(cookies.map((c) => c.name).sort()).toEqual(['JSESSIONID', 'SESSION', 'XSRF-TOKEN']);

    const sessao = cookies.find((c) => c.name === 'SESSION')!;
    expect(sessao.httpOnly).toBe(true);
    expect(sessao.secure).toBe(true);
    expect(sessao.sameSite).toBe('Lax');
    expect(sessao.path).toBe('/bff');

    const armazenamento = await page.evaluate(() => ({
      local: window.localStorage.length,
      sessao: window.sessionStorage.length,
    }));
    expect(armazenamento.local).toBe(0);
    expect(armazenamento.sessao).toBe(0);
  });

  test('AC22 (parcial): carteira paginada mostra somente os clientes do gerente autenticado, e a paginacao navega', async ({
    page,
  }) => {
    await logInAs(page, credentials['gerente.a'].login, credentials['gerente.a'].senha);

    const itens = page.locator('.lista-clientes li');
    await expect(itens.first()).toBeVisible();

    const paginacao = page.locator('.paginacao');
    await expect(paginacao.getByText(/^Pagina 1 de/)).toBeVisible();
    await expect(paginacao.getByRole('button', { name: 'Anterior' })).toBeDisabled();

    const nomesPagina1 = await itens.locator('.nome').allTextContents();
    expect(nomesPagina1.length).toBeGreaterThan(0);

    const botaoProxima = paginacao.getByRole('button', { name: 'Proxima' });
    if (await botaoProxima.isEnabled()) {
      await botaoProxima.click();
      await expect(paginacao.getByText(/^Pagina 2 de/)).toBeVisible();

      const nomesPagina2 = await page.locator('.lista-clientes li .nome').allTextContents();
      expect(nomesPagina2.length).toBeGreaterThan(0);
      expect(new Set(nomesPagina2).size + new Set(nomesPagina1).size).toBe(
        new Set([...nomesPagina1, ...nomesPagina2]).size,
      );
    }
  });

  test('AC22 (parcial): a carteira de um gerente nunca inclui clientes exclusivos de outro', async ({
    browser,
  }) => {
    const contextoA = await browser.newContext({ ignoreHTTPSErrors: true });
    const contextoB = await browser.newContext({ ignoreHTTPSErrors: true });
    try {
      const paginaA = await contextoA.newPage();
      const paginaB = await contextoB.newPage();

      await logInAs(paginaA, credentials['gerente.a'].login, credentials['gerente.a'].senha);
      await logInAs(paginaB, credentials['gerente.b'].login, credentials['gerente.b'].senha);

      await expect(paginaA.locator('.lista-clientes li').first()).toBeVisible();
      await expect(paginaB.locator('.lista-clientes li').first()).toBeVisible();

      const cpfsA = await paginaA.locator('.lista-clientes li .cpf').allTextContents();
      const cpfsB = await paginaB.locator('.lista-clientes li .cpf').allTextContents();

      expect(cpfsA.length).toBeGreaterThan(0);
      expect(cpfsB.length).toBeGreaterThan(0);
      expect(cpfsA.some((cpf) => cpfsB.includes(cpf))).toBe(false);
    } finally {
      await contextoA.close();
      await contextoB.close();
    }
  });

  test('AC20: uma escrita sem o token CSRF esperado e recusada, e o logout real invalida a sessao', async ({
    page,
  }) => {
    await logInAs(page, credentials['gerente.a'].login, credentials['gerente.a'].senha);

    // Requisicao de escrita sem X-XSRF-TOKEN: page.request compartilha os cookies do browser
    // (incluindo SESSION), mas nao replica o interceptor do Angular que anexa o header -- exatamente
    // o caso que o AC20 pede recusado.
    const semCsrf = await page.request.post('/bff/logout');
    expect(semCsrf.status()).toBe(403);

    // Sessao ainda valida: a tentativa acima foi recusada antes de alcancar o LogoutFilter.
    await expect(page.locator('.lista-clientes li').first()).toBeVisible();

    // Logout de verdade pelo botao "Sair": exercita o interceptor XSRF real do Angular, nao uma
    // copia manual do cookie.
    await page.getByRole('button', { name: 'Sair' }).click();
    await expect(page.getByRole('button', { name: 'Entrar' })).toBeVisible();

    const sessaoDepoisDoLogout = await page.request.get('/bff/api/sessao');
    expect(sessaoDepoisDoLogout.status()).toBe(401);
  });
});
