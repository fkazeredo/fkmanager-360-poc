import { test, expect, type Page } from '@playwright/test';
import { readDemoCredentials } from './demo-credentials';

/**
 * S7: jornada 2 -- rejeicao automatica (spec, S7; plano #0003, secao 7). Segunda das quatro
 * jornadas canonicas: straight-through approval (jornada 1, estendida em #0003), rejeicao
 * automatica (esta), callback perdido e indeterminacao (#0005/#0006). Mesmos helpers de
 * jornada1-primeiros-passos.spec.ts (login real, sem bypass de seguranca).
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

test.describe('Jornada 2 -- rejeicao automatica', () => {
  /**
   * Cliente "4" (EDUARDO HENRIQUE ROCHA), conta 10005, situacao BLOQUEADA no CoreLegado --
   * quarto item da carteira do gerente.a (indice 3, ordem de insercao do seed). A politica v1
   * avalia situacaoConta antes do eixo de risco: `CONTA_NAO_ELEGIVEL` recusa por situacao de
   * conta, mesmo com um limiteSolicitado que aumentaria o vigente e seria aceitavel sob outro
   * eixo. A rejeicao e terminal -- nunca "aguardando" nem marcada como pendente.
   */
  test('AC2: conta em situacao irregular e rejeitada com CONTA_NAO_ELEGIVEL, apresentada como terminal', async ({
    page,
  }) => {
    await logInAs(page, credentials['gerente.a'].login, credentials['gerente.a'].senha);

    await page.locator('.lista-clientes li .selecionar-cliente').nth(3).click();

    const atendimento = page.locator('.atendimento');
    await expect(atendimento).toBeVisible();
    await expect(atendimento.locator('.cliente-nome')).toContainText('EDUARDO HENRIQUE ROCHA');

    await atendimento.locator('.lista-contas .conta').first().click();
    await expect(atendimento.locator('.limite-vigente .valor')).toBeVisible();

    const formulario = atendimento.locator('.solicitacao-aumento-limite');
    await expect(formulario).toBeVisible();
    await formulario.locator('.limite-solicitado').fill('4000,00');
    await formulario.locator('button[type=submit]').click();

    const decisao = atendimento.locator('.decisao');
    await expect(decisao).toBeVisible();
    await expect(decisao).toHaveClass(/rejeitada/);
    await expect(decisao).not.toHaveClass(/aprovada/);
    await expect(decisao.locator('.status-solicitacao')).toContainText('REJEITADA');

    // Mensagem exata de CONTA_NAO_ELEGIVEL -- nunca a de FORA_DA_POLITICA_AUTOMATICA nem a de
    // PERFIL_RISCO_INCOMPATIVEL, que sao motivos distintos (spec, secao "Apresentacao").
    await expect(decisao.locator('.mensagem-decisao')).toContainText(
      'nao esta elegivel para aumento automatico de limite',
    );
    await expect(decisao.locator('.mensagem-decisao')).not.toContainText('politica de concessao automatica');
    await expect(decisao.locator('.mensagem-decisao')).not.toContainText('perfil');

    // Terminal: nenhuma marcacao de pendente/aguardando.
    await expect(decisao.locator('.limite-pendente')).toHaveCount(0);
    await expect(decisao).not.toContainText('aguardando confirmacao do Core');
  });
});
