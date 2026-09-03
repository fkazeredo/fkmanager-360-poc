package com.fkmanager360.credito.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisaoCreditoTest {

    private static final Instant DECIDIDA_EM = Instant.parse("2026-09-02T16:00:00Z");
    private static final VersaoPoliticaCredito V1 = new VersaoPoliticaCredito("v1");
    private static final AtorOperacao MOTOR = AtorSistema.MOTOR_DECISAO_CREDITO;

    @Test
    void resultadoCoerenteComOMotivo_eAceito() {
        var decisao = new DecisaoCredito(
                ResultadoDecisaoCredito.APROVADA, MotivoDecisaoCredito.DENTRO_DA_POLITICA_AUTOMATICA,
                V1, DECIDIDA_EM, MOTOR);

        assertThat(decisao.resultado()).isEqualTo(ResultadoDecisaoCredito.APROVADA);
    }

    @Test
    void resultadoDivergenteDoMotivo_lancaIllegalStateException() {
        assertThatThrownBy(() -> new DecisaoCredito(
                ResultadoDecisaoCredito.APROVADA, MotivoDecisaoCredito.CONTA_NAO_ELEGIVEL,
                V1, DECIDIDA_EM, MOTOR))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void camposObrigatorios_naoAceitamNulo() {
        assertThatThrownBy(() -> new DecisaoCredito(null, MotivoDecisaoCredito.CONTA_NAO_ELEGIVEL, V1, DECIDIDA_EM, MOTOR))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DecisaoCredito(ResultadoDecisaoCredito.REJEITADA, null, V1, DECIDIDA_EM, MOTOR))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DecisaoCredito(
                ResultadoDecisaoCredito.REJEITADA, MotivoDecisaoCredito.CONTA_NAO_ELEGIVEL, null, DECIDIDA_EM, MOTOR))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DecisaoCredito(
                ResultadoDecisaoCredito.REJEITADA, MotivoDecisaoCredito.CONTA_NAO_ELEGIVEL, V1, null, MOTOR))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DecisaoCredito(
                ResultadoDecisaoCredito.REJEITADA, MotivoDecisaoCredito.CONTA_NAO_ELEGIVEL, V1, DECIDIDA_EM, null))
                .isInstanceOf(NullPointerException.class);
    }
}
