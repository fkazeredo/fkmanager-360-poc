package com.fkmanager360.carteiraclientes.domain;

/**
 * O que este contexto sabe sobre um atendimento em curso: quem e o Cliente, e qual das contas
 * dele esta sendo atendida. Modelo de apresentacao, nao agregado -- exatamente como
 * {@link ClienteDaCarteira}.
 *
 * <p>Existir e, por si, a afirmacao de que o gerente tinha direito de atendimento atual sobre
 * aquele Cliente e de que a conta e mesmo dele; sem as duas coisas, este objeto nunca chega a
 * ser construido.
 */
public record ContextoAtendimento(ClienteId clienteId, DadosMestresCliente dadosMestres, ContaCorrente conta) {
}
