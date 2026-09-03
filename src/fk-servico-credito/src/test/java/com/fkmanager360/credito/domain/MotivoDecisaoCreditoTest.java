package com.fkmanager360.credito.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cada motivo carrega o seu resultado no proprio enum (ver Javadoc de MotivoDecisaoCredito) -- e
 * por isso a combinacao inconsistente nao existe: nao ha construtor publico nem setter que
 * permita associar um resultado diferente do que o enum ja fixou.
 */
class MotivoDecisaoCreditoTest {

    @Test
    void dentroDaPoliticaAutomatica_implicaAprovada() {
        assertThat(MotivoDecisaoCredito.DENTRO_DA_POLITICA_AUTOMATICA.resultado())
                .isEqualTo(ResultadoDecisaoCredito.APROVADA);
    }

    @Test
    void contaNaoElegivel_implicaRejeitada() {
        assertThat(MotivoDecisaoCredito.CONTA_NAO_ELEGIVEL.resultado())
                .isEqualTo(ResultadoDecisaoCredito.REJEITADA);
    }

    @Test
    void perfilRiscoIncompativel_implicaRejeitada() {
        assertThat(MotivoDecisaoCredito.PERFIL_RISCO_INCOMPATIVEL.resultado())
                .isEqualTo(ResultadoDecisaoCredito.REJEITADA);
    }

    @Test
    void foraDaPoliticaAutomatica_implicaRejeitada() {
        assertThat(MotivoDecisaoCredito.FORA_DA_POLITICA_AUTOMATICA.resultado())
                .isEqualTo(ResultadoDecisaoCredito.REJEITADA);
    }

    @ParameterizedTest
    @EnumSource(MotivoDecisaoCredito.class)
    void todoMotivo_temResultadoNaoNulo(MotivoDecisaoCredito motivo) {
        assertThat(motivo.resultado()).isNotNull();
    }

    @Test
    void existeExatamenteUmMotivoAprovado_osDemaisSaoRejeicao() {
        long aprovados = java.util.Arrays.stream(MotivoDecisaoCredito.values())
                .filter(m -> m.resultado() == ResultadoDecisaoCredito.APROVADA)
                .count();
        assertThat(aprovados).isEqualTo(1);
    }
}
