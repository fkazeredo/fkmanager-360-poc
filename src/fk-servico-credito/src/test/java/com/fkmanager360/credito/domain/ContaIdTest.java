package com.fkmanager360.credito.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A canonicalizacao vive no value object (I5 do review de #0002): o mesmo numero de conta,
 * padded ou nao, precisa produzir a mesma identidade -- e por isso o mesmo comportamento de
 * autorizacao -- em toda a plataforma.
 */
class ContaIdTest {

    @Test
    void semPadding_permaneceIgual() {
        assertThat(new ContaId("10001").valor()).isEqualTo("10001");
    }

    @Test
    void comPadding_canonicalizaParaSemPadding() {
        assertThat(new ContaId("0000010001").valor()).isEqualTo("10001");
    }

    @Test
    void padded_eNaoPadded_saoAMesmaIdentidade() {
        assertThat(new ContaId("0000010001")).isEqualTo(new ContaId("10001"));
        assertThat(new ContaId("0000010001").hashCode()).isEqualTo(new ContaId("10001").hashCode());
    }

    @Test
    void todosZeros_canonicalizaParaZero() {
        assertThat(new ContaId("0000000000").valor()).isEqualTo("0");
    }

    @Test
    void naoNumerico_eRecusado() {
        assertThatThrownBy(() -> new ContaId("ABC0010001")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void maisDeDezDigitos_eRecusado() {
        assertThatThrownBy(() -> new ContaId("123456789012")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nulo_eRecusado() {
        assertThatThrownBy(() -> new ContaId(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
