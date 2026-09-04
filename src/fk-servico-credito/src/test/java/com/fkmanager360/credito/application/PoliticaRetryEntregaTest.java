package com.fkmanager360.credito.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S1 (ADR-0018): backoff exponencial com jitter do dispatcher de efetivacao (plano #0004, secao
 * 5 -- OD-2), puro em JUnit, sem Spring nem sleep real. {@link RandomEmValorFixo} substitui
 * {@link Random#nextDouble()} por um valor determinístico -- assim o jitter em si e verificavel
 * sem depender de seed/estatistica.
 */
class PoliticaRetryEntregaTest {

    private static final Duration BASE = Duration.ofSeconds(1);
    private static final Duration TETO = Duration.ofSeconds(4);
    private static final double JITTER_FATOR = 0.2;

    @Test
    void calcularEspera_semJitter_cresceExponencialmenteAPartirDaBase() {
        PoliticaRetryEntrega politica = politicaSemJitter();

        assertThat(politica.calcularEspera(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(politica.calcularEspera(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(politica.calcularEspera(3)).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void calcularEspera_alemDoTeto_permaneceNoTeto() {
        PoliticaRetryEntrega politica = politicaSemJitter();

        assertThat(politica.calcularEspera(4)).isEqualTo(Duration.ofSeconds(4));
        assertThat(politica.calcularEspera(10)).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void calcularEspera_jitterMaximo_aumentaEmAteOFatorConfigurado() {
        PoliticaRetryEntrega politica = new PoliticaRetryEntrega(BASE, TETO, JITTER_FATOR, new RandomEmValorFixo(1.0));

        assertThat(politica.calcularEspera(1)).isEqualTo(Duration.ofMillis(1200));
    }

    @Test
    void calcularEspera_jitterMinimo_reduzEmAteOFatorConfigurado() {
        PoliticaRetryEntrega politica = new PoliticaRetryEntrega(BASE, TETO, JITTER_FATOR, new RandomEmValorFixo(0.0));

        assertThat(politica.calcularEspera(1)).isEqualTo(Duration.ofMillis(800));
    }

    @Test
    void calcularEspera_tentativaMenorQueUm_lancaExcecao() {
        PoliticaRetryEntrega politica = politicaSemJitter();

        assertThatThrownBy(() -> politica.calcularEspera(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void construtor_tetoMenorQueBase_lancaExcecao() {
        assertThatThrownBy(() -> new PoliticaRetryEntrega(Duration.ofSeconds(4), Duration.ofSeconds(1), JITTER_FATOR, new Random()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void construtor_jitterForaDoIntervalo_lancaExcecao() {
        assertThatThrownBy(() -> new PoliticaRetryEntrega(BASE, TETO, 1.5, new Random()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PoliticaRetryEntrega politicaSemJitter() {
        return new PoliticaRetryEntrega(BASE, TETO, JITTER_FATOR, new RandomEmValorFixo(0.5));
    }

    /** nextDouble() = 0.5 neutraliza o jitter (fator resultante = 1.0); 0.0 e 1.0 atingem os extremos. */
    private static final class RandomEmValorFixo extends Random {
        private final double valor;

        RandomEmValorFixo(double valor) {
            this.valor = valor;
        }

        @Override
        public double nextDouble() {
            return valor;
        }
    }
}
