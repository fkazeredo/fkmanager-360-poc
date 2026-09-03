package com.fkmanager360.credito.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncrementoSolicitadoTest {

    @Test
    void positivo_eAceito() {
        assertThat(new IncrementoSolicitado(100_000).centavos()).isEqualTo(100_000);
    }

    @Test
    void zero_eRecusado() {
        assertThatThrownBy(() -> new IncrementoSolicitado(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativo_eRecusado() {
        assertThatThrownBy(() -> new IncrementoSolicitado(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
