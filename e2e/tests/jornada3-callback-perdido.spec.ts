import { test, expect, type Page } from '@playwright/test';
import { readDemoCredentials } from './demo-credentials';
import { pollCreditoDbScalarUntil, queryCreditoDbScalar } from './db-credito';
import { limparCenario, suprimirCallback } from './control-plane';

/**
 * S7, jornada 3 -- callback perdido recuperado pela reconciliacao (spec, secao "Reconciliacao";
 * ADR-0009 emenda; #0006, AC12). Terceira das quatro jornadas canonicas. Usa a carteira do
 * gerente.b (clientes 101/102/103, uma unica pagina, nunca tocada pelas jornadas 1/2) para nao
 * colidir com o estado que aquelas jornadas ja deixaram em credito_db.
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

test.describe('Jornada 3 -- callback perdido recuperado pela reconciliacao', () => {
  test.beforeAll(async () => {
    await limparCenario('20001');
  });

  test.afterAll(async () => {
    await limparCenario('20001');
  });

  /**
   * Conta 20001 (cliente IGOR BARBOSA TEIXEIRA, vigente real 450000 centavos, risco BAIXO): o
   * callback de confirmacao e suprimido no simulador ANTES da submissao -- o processamento
   * assincrono ainda muda o limite e registra o desfecho normalmente (AC12), so a notificacao
   * nunca chega. So a reconciliacao, perguntando periodicamente ao Core, converge a solicitacao
   * para EFETIVADA -- sem que o dispatcher jamais reenvie a instrucao (uma unica
   * EFETIVACAO_SOLICITADA no historico).
   */
  test('AC12: callback suprimido -- a reconciliacao converge para EFETIVADA sem reenviar a instrucao', async ({
    page,
  }) => {
    await suprimirCallback('20001');

    await logInAs(page, credentials['gerente.b'].login, credentials['gerente.b'].senha);

    await page.locator('.lista-clientes li .selecionar-cliente').first().click();

    const atendimento = page.locator('.atendimento');
    await expect(atendimento).toBeVisible();
    await expect(atendimento.locator('.cliente-nome')).toContainText('IGOR BARBOSA TEIXEIRA');

    const contas = atendimento.locator('.lista-contas .conta');
    await expect(contas.first()).toBeVisible();
    await contas.first().click();

    const limiteVigente = atendimento.locator('.limite-vigente .valor');
    await expect(limiteVigente).toHaveText(/R\$\s*4\.500,00/);

    const formulario = atendimento.locator('.solicitacao-aumento-limite');
    await expect(formulario).toBeVisible();
    await formulario.locator('.limite-solicitado').fill('5500,00');

    const respostaSubmissao = page.waitForResponse(
      (resposta) => resposta.url().includes('/solicitacoes-aumento-limite') && resposta.request().method() === 'POST',
    );
    await formulario.locator('button[type=submit]').click();
    const solicitacaoId = (await (await respostaSubmissao).json()).solicitacaoId as string;
    expect(solicitacaoId).toMatch(/^[0-9a-f-]{36}$/);

    const decisao = atendimento.locator('.decisao');
    await expect(decisao).toBeVisible();
    await expect(decisao).toHaveClass(/aprovada/);
    await expect(decisao.locator('.status-tom-acompanhamento')).toBeVisible();

    // Sem callback, so a reconciliacao periodica converge -- a janela de demonstracao deste
    // Compose (CREDITO_RECONCILIACAO_ELEGIVEL_APOS=PT3S, poll a cada 1s) fecha em poucos segundos.
    await pollCreditoDbScalarUntil(
      `select status from solicitacao_aumento_limite where id = '${solicitacaoId}'`,
      'EFETIVADA',
      20_000,
    );

    // AC12: o historico atribui a conclusao ao mecanismo de reconciliacao -- nunca ao CORE_LEGADO
    // diretamente, porque desta vez ninguem recebeu um callback dele.
    expect(
      queryCreditoDbScalar(
        `select ator_id from historico_solicitacao where solicitacao_id = '${solicitacaoId}' and tipo_fato = 'RESULTADO_EFETIVACAO_REGISTRADO'`,
      ),
    ).toBe('RECONCILIACAO_EFETIVACAO');

    // Nenhuma segunda efetivacao foi enviada: EFETIVACAO_SOLICITADA aparece exatamente uma vez.
    expect(
      queryCreditoDbScalar(
        `select count(*) from historico_solicitacao where solicitacao_id = '${solicitacaoId}' and tipo_fato = 'EFETIVACAO_SOLICITADA'`,
      ),
    ).toBe('1');

    // O vigente exibido reflete o novo valor apos a conclusao. Gerente.b so tem UMA conta por
    // cliente (ao contrario do cliente 1 da jornada 1): clicar de novo no MESMO botao ainda forca
    // uma nova consulta -- o handler empurra para o Subject a cada clique, sem distinctUntilChanged.
    await expect(async () => {
      await contas.first().click();
      await expect(limiteVigente).toHaveText(/R\$\s*5\.500,00/);
    }).toPass({ timeout: 15_000, intervals: [500] });
  });
});
