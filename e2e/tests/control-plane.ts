/**
 * Cliente HTTP fino para o control plane de cenarios do simulador-core-legado (ADR-0018, #0006):
 * suprimir-callback (jornada 3) e suspender-processamento/liberar (jornada 4). Publicado apenas
 * em loopback pelo Compose (compose.yaml), e so porque este control plane ja e exclusivo dos
 * profiles local/demo/test -- nunca uma capacidade real do CoreLegado.
 */
const CONTROL_PLANE_BASE_URL = 'http://127.0.0.1:8090/control-plane/efetivacoes';

/** {@code ContaId} sem zero-padding -> numCta host-centric de 10 digitos (ADR-0005). */
function numCtaHost(contaId: string): string {
  return contaId.padStart(10, '0');
}

async function chamar(metodo: 'POST' | 'DELETE', caminho: string): Promise<Response> {
  const resposta = await fetch(`${CONTROL_PLANE_BASE_URL}${caminho}`, { method: metodo });
  return resposta;
}

export async function suprimirCallback(contaId: string): Promise<void> {
  const resposta = await chamar('POST', `/${numCtaHost(contaId)}/suprimir-callback`);
  if (!resposta.ok) {
    throw new Error(`control-plane suprimir-callback falhou para ${contaId}: ${resposta.status}`);
  }
}

export async function suspenderProcessamento(contaId: string): Promise<void> {
  const resposta = await chamar('POST', `/${numCtaHost(contaId)}/suspender-processamento`);
  if (!resposta.ok) {
    throw new Error(`control-plane suspender-processamento falhou para ${contaId}: ${resposta.status}`);
  }
}

/** Idempotente na ausencia de pendencia por design deste harness: chamadores verificam o efeito via DB. */
export async function liberarProcessamento(contaId: string): Promise<void> {
  const resposta = await chamar('POST', `/${numCtaHost(contaId)}/liberar`);
  if (!resposta.ok && resposta.status !== 404) {
    throw new Error(`control-plane liberar falhou para ${contaId}: ${resposta.status}`);
  }
}

export async function limparCenario(contaId: string): Promise<void> {
  const resposta = await chamar('DELETE', `/${numCtaHost(contaId)}`);
  if (!resposta.ok) {
    throw new Error(`control-plane limpar cenario falhou para ${contaId}: ${resposta.status}`);
  }
}
