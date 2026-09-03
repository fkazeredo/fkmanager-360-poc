package com.fkmanager360.credito.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimiteSolicitadoTest {

    @Test
    void positivo_eAceito() {
        assertThat(new LimiteSolicitado(600_000).centavos()).isEqualTo(600_000);
    }

    @Test
    void zero_eRecusado() {
        assertThatThrownBy(() -> new LimiteSolicitado(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativo_eRecusado() {
        assertThatThrownBy(() -> new LimiteSolicitado(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
