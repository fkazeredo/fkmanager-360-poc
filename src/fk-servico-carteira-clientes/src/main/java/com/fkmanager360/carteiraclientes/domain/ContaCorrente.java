package com.fkmanager360.carteiraclientes.domain;

/**
 * A {@code ContaCorrente} como este contexto a conhece: a conta que pertence a determinado
 * Cliente e que determinado gerente tem direito de atender. Identificacao, e nada mais --
 * limite, saldo e situacao pertencem a Credito, pela ACL daquele contexto (ADR-0004, AC30).
 *
 * <p>Nao carrega {@code clienteId} de proposito. O vinculo conta-cliente nao e afirmado por um
 * campo copiado, e sim pelo fato de o CoreLegado ter devolvido esta conta ao ser perguntado
 * pelas contas daquele Cliente -- que e a unica pergunta que este contexto faz ao Core, e sempre
 * com uma chave ja autorizada.
 */
public record ContaCorrente(ContaId contaId, String agencia) {
}
