package com.fkmanager360.credito.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dominio puro, JUnit sem Spring: as poucas regras que existem neste ticket. O modelo de Credito
 * e deliberadamente fino aqui -- SolicitacaoAumentoLimite, ContextoDecisaoCredito e
 * PoliticaCredito nascem quando a spec os exigir (ADR-0010).
 */
class DadosCreditoCoreTest {

    private static final Instant CONSULTADO_EM = Instant.parse("2026-09-02T16:00:00Z");

    @Test
    void limiteNegativo_naoExisteNoContrato_eERecusado() {
        assertThatThrownBy(() -> new LimiteChequeEspecialVigente(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limiteZero_eValido_significaClienteSemChequeEspecial() {
        assertThat(new LimiteChequeEspecialVigente(0).centavos()).isZero();
    }

    @Test
    void procedenciaSemFonte_naoEProcedencia() {
        assertThatThrownBy(() -> new DadosCreditoCore(
                new LimiteChequeEspecialVigente(500_000), SituacaoConta.REGULAR,
                ClassificacaoRiscoCreditoBase.BAIXO, CONSULTADO_EM, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void semInstanteDeConsulta_naoHaComoAfirmarQueOsFatosSaoDeAgora() {
        assertThatThrownBy(() -> new DadosCreditoCore(
                new LimiteChequeEspecialVigente(500_000), SituacaoConta.REGULAR,
                ClassificacaoRiscoCreditoBase.BAIXO, null, "CoreLegado"))
                .isInstanceOf(NullPointerException.class);
    }
}
