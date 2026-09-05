import { test, expect, type Page } from '@playwright/test';
import { readDemoCredentials } from './demo-credentials';
import { pollCreditoDbScalarUntil, queryCreditoDbScalar } from './db-credito';
import { limparCenario, liberarProcessamento, suspenderProcessamento } from './control-plane';

/**
 * S7, jornada 4 -- EFETIVACAO_INDETERMINADA seguida de conclusao tardia (spec, secao
 * "Reconciliacao"; ADR-0009, emenda; #0006, AC16/AC35/AC37). Quarta e ultima das jornadas
 * canonicas da spec -- com ela o conjunto de quatro fecha. Usa a carteira do gerente.b, cliente
 * JULIANA RIBEIRO MOURA (conta 20002), nunca tocada pelas jornadas 1/2/3.
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

test.describe('Jornada 4 -- efetivacao indeterminada com conclusao tardia', () => {
  test.beforeAll(async () => {
    await limparCenario('20002');
  });

  test.afterAll(async () => {
    await limparCenario('20002');
  });

  /**
   * Conta 20002 (cliente JULIANA RIBEIRO MOURA, vigente real 600000 centavos, risco MEDIO): o
   * processamento no simulador fica suspenso -- nem o limite muda, nem o callback dispara -- ate
   * ser liberado explicitamente. Nem o dispatcher (uma unica entrega, AC28) nem a reconciliacao
   * (so pergunta, nunca reenvia) produzem resultado autoritativo dentro da janela normal
   * (CREDITO_RECONCILIACAO_JANELA=PT10S neste Compose): a solicitacao entra em
   * EFETIVACAO_INDETERMINADA, que bloqueia nova solicitacao para a mesma conta e se apresenta
   * como acompanhamento, nunca como erro. Uma resposta tardia (liberar) ainda a conclui.
   */
  test('AC16/AC35/AC37: janela esgotada vira EFETIVACAO_INDETERMINADA, bloqueia nova solicitacao, e uma resposta tardia conclui', async ({
    page,
  }) => {
    await suspenderProcessamento('20002');

    await logInAs(page, credentials['gerente.b'].login, credentials['gerente.b'].senha);

    await page.locator('.lista-clientes li .selecionar-cliente').nth(1).click();

    const atendimento = page.locator('.atendimento');
    await expect(atendimento).toBeVisible();
    await expect(atendimento.locator('.cliente-nome')).toContainText('JULIANA RIBEIRO MOURA');

    const contas = atendimento.locator('.lista-contas .conta');
    await expect(contas.first()).toBeVisible();
    await contas.first().click();

    const limiteVigente = atendimento.locator('.limite-vigente .valor');
    await expect(limiteVigente).toHaveText(/R\$\s*6\.000,00/);

    const formulario = atendimento.locator('.solicitacao-aumento-limite');
    await expect(formulario).toBeVisible();
    await formulario.locator('.limite-solicitado').fill('7000,00');

    const respostaSubmissao = page.waitForResponse(
      (resposta) => resposta.url().includes('/solicitacoes-aumento-limite') && resposta.request().method() === 'POST',
    );
    await formulario.locator('button[type=submit]').click();
    const solicitacaoId = (await (await respostaSubmissao).json()).solicitacaoId as string;
    expect(solicitacaoId).toMatch(/^[0-9a-f-]{36}$/);

    const decisao = atendimento.locator('.decisao');
    await expect(decisao).toBeVisible();
    await expect(decisao).toHaveClass(/aprovada/);
    // AC37: tom de acompanhamento, nunca de erro -- a superficie viva de EFETIVACAO_INDETERMINADA
    // em si (refletir o status apos a janela esgotar) pertence a #0007; aqui fecha o catalogo e o
    // caminho de renderizacao (mensagens.spec.ts cobre a taxonomia completa, unitariamente).
    await expect(decisao.locator('.status-tom-acompanhamento')).toBeVisible();

    // AC16/AC35: a janela normal (~10s neste Compose) esgota sem resultado autoritativo -- nem o
    // dispatcher (que so entrega, nao pergunta) nem a reconciliacao (que consultou e so encontrou
    // "ainda em processamento", porque o simulador esta suspenso) concluem nada.
    await pollCreditoDbScalarUntil(
      `select status from solicitacao_aumento_limite where id = '${solicitacaoId}'`,
      'EFETIVACAO_INDETERMINADA',
      25_000,
    );
    expect(
      queryCreditoDbScalar(
        `select ator_id from historico_solicitacao where solicitacao_id = '${solicitacaoId}' and tipo_fato = 'EFETIVACAO_INDETERMINADA_REGISTRADA'`,
      ),
    ).toBe('RECONCILIACAO_EFETIVACAO');

    // AC57/AC37: uma nova solicitacao para a MESMA conta e recusada -- a UI bloqueia com o aviso
    // de processo em andamento, nunca com uma mensagem de erro tecnico.
    await formulario.locator('.limite-solicitado').fill('6500,00');
    await formulario.locator('button[type=submit]').click();

    const avisoBloqueio = atendimento.locator('.erro-submissao');
    await expect(avisoBloqueio).toContainText('Ja existe um processo em andamento para esta conta');
    await expect(formulario.locator('button[type=submit]')).toBeDisabled();

    // AC16: uma resposta autoritativa tardia (aqui, o processamento liberado manualmente) ainda
    // conclui a operacao -- ignorancia recuperavel, nunca falha definitiva.
    await liberarProcessamento('20002');

    await pollCreditoDbScalarUntil(
      `select status from solicitacao_aumento_limite where id = '${solicitacaoId}'`,
      'EFETIVADA',
      15_000,
    );
    expect(
      queryCreditoDbScalar(
        `select count(*) from historico_solicitacao where solicitacao_id = '${solicitacaoId}' and tipo_fato = 'RESULTADO_EFETIVACAO_REGISTRADO'`,
      ),
    ).toBe('1');
  });
});
