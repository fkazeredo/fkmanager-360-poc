package com.fkmanager360.credito.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * User Story 16: a OrigemSolicitacao e estabelecida pelo dominio e nunca aceita do cliente HTTP.
 * Modelada como enum de um so valor -- a unica forma publica de obter uma instancia e a propria
 * constante.
 */
class OrigemSolicitacaoTest {

    @Test
    void unicoValorExistente_eCliente() {
        assertThat(OrigemSolicitacao.values()).containsExactly(OrigemSolicitacao.CLIENTE);
    }

    @Test
    void aUnicaFormaPublicaDeObterUmaInstancia_eAConstanteCliente() {
        assertThat(OrigemSolicitacao.valueOf("CLIENTE")).isSameAs(OrigemSolicitacao.CLIENTE);
    }
}
