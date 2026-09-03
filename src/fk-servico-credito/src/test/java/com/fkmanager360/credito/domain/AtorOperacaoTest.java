package com.fkmanager360.credito.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AtorOperacao e sealed com exatamente dois casos (CONTEXT.md raiz, secao "Atores"):
 * AtorHumano e AtorSistema.
 */
class AtorOperacaoTest {

    @Test
    void atorHumano_carregaOAtorId() {
        AtorOperacao ator = new AtorHumano(new AtorId("gerente-42"));
        assertThat(ator).isInstanceOf(AtorHumano.class);
        assertThat(((AtorHumano) ator).id().valor()).isEqualTo("gerente-42");
    }

    @Test
    void atorHumano_naoAceitaIdNulo() {
        assertThatThrownBy(() -> new AtorHumano(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void atorSistema_motorDecisaoCredito_eOnomeProprioEsperado() {
        assertThat(AtorSistema.MOTOR_DECISAO_CREDITO.nome()).isEqualTo("MOTOR_DECISAO_CREDITO");
    }

    @Test
    void atorSistema_naoAceitaNomeVazio() {
        assertThatThrownBy(() -> new AtorSistema("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AtorSistema(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void switchExaustivoSobreOSealed_cobreOsDoisCasosSemDefault() {
        AtorOperacao ator = AtorSistema.MOTOR_DECISAO_CREDITO;
        String descricao = switch (ator) {
            case AtorHumano humano -> "humano:" + humano.id().valor();
            case AtorSistema sistema -> "sistema:" + sistema.nome();
        };
        assertThat(descricao).isEqualTo("sistema:MOTOR_DECISAO_CREDITO");
    }
}
