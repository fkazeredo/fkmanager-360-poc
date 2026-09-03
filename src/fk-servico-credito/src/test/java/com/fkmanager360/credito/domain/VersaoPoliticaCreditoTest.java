package com.fkmanager360.credito.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersaoPoliticaCreditoTest {

    @Test
    void valorNaoVazio_eAceito() {
        assertThat(new VersaoPoliticaCredito("v1").valor()).isEqualTo("v1");
    }

    @Test
    void nula_eRecusada() {
        assertThatThrownBy(() -> new VersaoPoliticaCredito(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emBranco_eRecusada() {
        assertThatThrownBy(() -> new VersaoPoliticaCredito("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duasInstanciasComMesmoValor_saoIguais() {
        assertThat(new VersaoPoliticaCredito("v1")).isEqualTo(new VersaoPoliticaCredito("v1"));
    }
}
