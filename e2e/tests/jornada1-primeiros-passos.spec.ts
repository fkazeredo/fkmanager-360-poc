import { test, expect, type Page } from '@playwright/test';
import { execSync } from 'node:child_process';
import path from 'node:path';
import { readDemoCredentials } from './demo-credentials';

const repoRoot = path.resolve(__dirname, '..', '..');

/**
 * S7: harness Playwright contra a stack real do Compose, sem bypass de seguranca. Estas asercoes
 * sao os primeiros passos da jornada 1 (AC19, AC20, AC21, AC22, AC29 parcial, AC30) -- a jornada
 * so fecha em #0005, entao nenhuma etapa alem de login, carteira, selecao de conta e leitura do
 * limite e exercitada aqui. Nenhuma jornada nova e criada: as quatro canonicas da spec continuam
 * sendo as mesmas.
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

  /**
   * O passo que #0002 acrescenta a jornada: escolher o Cliente, escolher a ContaCorrente e ver o
   * LimiteChequeEspecialVigente. Prova a topologia inteira de uma vez -- o Token Exchange
   * encadeado (AC21), a autorizacao de recurso nos dois backends (AC23) e a composicao do BFF a
   * partir dos dois contextos (AC30) --, coisas que so falham de verdade com a stack de pe.
   */
  test('AC22/AC29: selecionar cliente e conta mostra o LimiteChequeEspecialVigente vindo do Core', async ({
    page,
  }) => {
    await logInAs(page, credentials['gerente.a'].login, credentials['gerente.a'].senha);

    await page.locator('.lista-clientes li .selecionar-cliente').first().click();

    const atendimento = page.locator('.atendimento');
    await expect(atendimento).toBeVisible();
    await expect(atendimento.locator('.cliente-nome')).toHaveText(/\S/);

    // O cliente 1 tem duas contas no dataset do simulador: escolher a conta certa antes de
    // qualquer solicitacao precisa ser uma escolha de verdade.
    const contas = atendimento.locator('.lista-contas .conta');
    await expect(contas.first()).toBeVisible();
    expect(await contas.count()).toBeGreaterThan(1);

    await contas.first().click();

    const limite = atendimento.locator('.limite-vigente .valor');
    await expect(limite).toBeVisible();
    // O valor exato semeado no simulador para esta conta (500000 centavos), formatado em pt-BR
    // pelo Angular: prova que o que a tela mostra e o que o CoreLegado reconhece, atravessando a
    // ACL de Credito e a composicao do BFF sem ser reinterpretado no caminho (AC29, ADR-0002).
    await expect(limite).toHaveText(/R\$\s*5\.000,00/);
    await expect(atendimento.locator('.limite-vigente .procedencia')).toContainText('CoreLegado');
  });

  test('AC30: a tela de atendimento e composta pelo bff-gerente, e o browser nunca fala com outro backend', async ({
    page,
  }) => {
    const origensChamadas = new Set<string>();
    page.on('request', (requisicao) => {
      const url = new URL(requisicao.url());
      origensChamadas.add(url.origin);
    });

    await logInAs(page, credentials['gerente.a'].login, credentials['gerente.a'].senha);
    await page.locator('.lista-clientes li .selecionar-cliente').first().click();
    await page.locator('.atendimento .lista-contas .conta').first().click();
    await expect(page.locator('.limite-vigente .valor')).toBeVisible();

    // Um unico endereco publico: nem servico-credito, nem servico-carteira-clientes, nem o
    // simulador tem porta publicada -- a composicao acontece no servidor (AC30).
    expect([...origensChamadas]).toEqual(['https://localhost:4200']);
  });

  test('AC23: sem direito de atendimento, o backend recusa mesmo sem passar pela navegacao do Angular', async ({
    page,
  }) => {
    await logInAs(page, credentials['gerente.a'].login, credentials['gerente.a'].senha);

    // Cliente 101 pertence a carteira do gerente.b. A requisicao vai direto ao BFF, sem passar
    // por botao algum: ocultar opcoes no Angular nunca e o controle (ADR-0007).
    const contasDeOutraCarteira = await page.request.get('/bff/api/clientes/101/contas');
    expect(contasDeOutraCarteira.status()).toBe(403);

    const atendimentoDeOutraCarteira = await page.request.get(
      '/bff/api/clientes/101/contas/20001/atendimento',
    );
    expect(atendimentoDeOutraCarteira.status()).toBe(403);

    // Cliente 999 existe no CoreLegado e nao esta na carteira de ninguem: existir no Core,
    // isolado, nao concede acesso.
    const clienteSemCarteira = await page.request.get('/bff/api/clientes/999/contas');
    expect(clienteSemCarteira.status()).toBe(403);
  });

  test('AC23: uma conta que nao e do Cliente autorizado nao produz atendimento', async ({ page }) => {
    await logInAs(page, credentials['gerente.a'].login, credentials['gerente.a'].senha);

    // O gerente tem direito sobre o Cliente 1, e manda a conta 10003, que e do Cliente 2. Quem
    // afirma a quem a conta pertence e o CoreLegado, nunca o payload de quem chamou.
    const parIncoerente = await page.request.get('/bff/api/clientes/1/contas/10003/atendimento');
    expect(parIncoerente.status()).toBe(404);
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

  /**
   * AC20: a sessao sobrevive ao restart da instancia do bff-gerente que a originou. Redis real
   * sendo o backing store (S6, BffSegurancaTest) e evidencia forte mas indireta -- so um restart
   * de verdade prova que nenhum estado sobrevive apenas na instancia antiga. `docker compose
   * restart` (nao `up --force-recreate`) e deliberado: mantem Redis vivo e derruba/sobe somente
   * o processo do bff-gerente, exatamente o cenario do AC. Sem sleep fixo: `expect.poll` reage
   * ao healthcheck real (o mesmo endpoint que o Compose usa para orquestrar depends_on).
   */
  test('AC20: a sessao sobrevive ao restart da instancia do bff-gerente que a originou', async ({
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

    const cookiesAntes = await context.cookies();
    const sessaoAntes = cookiesAntes.find((c) => c.name === 'SESSION');
    expect(sessaoAntes, 'login deveria ter estabelecido um cookie de sessao').toBeTruthy();

    execSync('docker compose restart bff-gerente', { cwd: repoRoot, stdio: 'pipe' });

    // O restart derruba a instancia; ate a nova responder, /bff/ fica inalcancavel pelo nginx --
    // por isso failOnStatusCode:false (uma resposta de erro de proxy tambem e "ainda nao pronto").
    await expect
      .poll(
        async () => {
          const resposta = await page.request.get('/bff/actuator/health', { failOnStatusCode: false });
          return resposta.status();
        },
        { timeout: 60_000, intervals: [500] },
      )
      .toBe(200);

    // Mesmo cookie, nenhum novo login: se a sessao so existisse na instancia derrubada, isto
    // voltaria 401 e o teste falharia aqui.
    const sessaoDepoisDoRestart = await page.request.get('/bff/api/sessao');
    expect(sessaoDepoisDoRestart.status()).toBe(200);
    expect(await sessaoDepoisDoRestart.json()).toEqual({ gerenteId: credentials['gerente.a'].login });

    // A carteira tambem responde sem re-login, na mesma pagina/context que autenticou antes do restart.
    await page.reload();
    await expect(page.locator('.lista-clientes li').first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Entrar' })).not.toBeVisible();

    expect(respostasComToken).toEqual([]);
  });

  /**
   * #0003 estende a jornada 1: submissao -> decisao automatica -> AGUARDANDO_EFETIVACAO
   * (AC1 parcial, AC29 parcial). Usa a conta 10002 (SEGUNDA conta do cliente 1, risco MEDIO,
   * vigente R$ 1.200,00) -- deliberadamente NAO a 10001, que fica livre em SOLICITADA/estado
   * terminal nenhum para que os testes de AC22/AC29 acima continuem repetiveis. Um incremento de
   * R$ 800,00 (vigente 1.200,00 -> solicitado 2.000,00) fica dentro da politica v1 (risco MEDIO
   * permitido, total <= R$ 10.000,00, incremento <= R$ 2.000,00) -> APROVADA.
   */
  test('AC1/AC29 (parcial): submissao dentro da politica automatica aprova e mostra o vigente do Core junto do solicitado pendente', async ({
    page,
  }) => {
    await logInAs(page, credentials['gerente.a'].login, credentials['gerente.a'].senha);

    await page.locator('.lista-clientes li .selecionar-cliente').first().click();

    const atendimento = page.locator('.atendimento');
    await expect(atendimento).toBeVisible();

    const contas = atendimento.locator('.lista-contas .conta');
    await expect(contas.nth(1)).toBeVisible();
    await contas.nth(1).click();

    const limiteInicial = atendimento.locator('.limite-vigente .valor');
    await expect(limiteInicial).toHaveText(/R\$\s*1\.200,00/);

    const formulario = atendimento.locator('.solicitacao-aumento-limite');
    await expect(formulario).toBeVisible();
    await formulario.locator('.limite-solicitado').fill('2000,00');
    await formulario.locator('button[type=submit]').click();

    const decisao = atendimento.locator('.decisao');
    await expect(decisao).toBeVisible();
    await expect(decisao).toHaveClass(/aprovada/);
    await expect(decisao.locator('.status-solicitacao')).toContainText('AGUARDANDO_EFETIVACAO');

    // O vigente confirmado pelo Core continua sendo o ANTIGO -- o solicitado aparece marcado
    // como pendente, nunca substituindo o vigente antes da confirmacao autoritativa (AC29).
    await expect(decisao.locator('.limite-vigente-confirmado')).toHaveText(/R\$\s*1\.200,00/);
    await expect(decisao.locator('.limite-pendente')).toContainText(/R\$\s*2\.000,00/);
    await expect(decisao.locator('.limite-pendente')).toContainText('aguardando confirmacao do Core');
  });
});
