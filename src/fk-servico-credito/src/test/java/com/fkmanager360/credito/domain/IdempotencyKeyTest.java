package com.fkmanager360.credito.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Envelope minimo em torno do header "Idempotency-Key" (nome verbatim, ver Javadoc da classe).
 */
class IdempotencyKeyTest {

    @Test
    void encapsulaOUuidRecebido() {
        UUID valor = UUID.randomUUID();
        assertThat(new IdempotencyKey(valor).valor()).isEqualTo(valor);
    }

    @Test
    void nula_eRecusada() {
        assertThatThrownBy(() -> new IdempotencyKey(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void mesmoUuid_eAMesmaIdentidade() {
        UUID valor = UUID.randomUUID();
        assertThat(new IdempotencyKey(valor)).isEqualTo(new IdempotencyKey(valor));
    }
}
