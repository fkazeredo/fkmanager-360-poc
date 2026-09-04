package com.fkmanager360.credito.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Random;

/**
 * Backoff exponencial com jitter para o dispatcher de efetivacao (spec, secao "Dispatcher"; plano
 * #0004, secao 5 -- OD-2: mecanismo persistido em {@code proxima_tentativa_em}, nunca
 * {@code Thread.sleep}). Puro: sem Spring, sem I/O -- {@link Random} e injetado para que os testes
 * S1 sejam deterministicos sem depender de estatistica (ADR-0018).
 *
 * <p>O calculo aplica o teto ANTES do jitter: o jitter pode empurrar o resultado um pouco acima do
 * teto configurado, o que e intencional (o teto e o alvo de demonstracao, nao um limite rigido).
 */
public final class PoliticaRetryEntrega {

    private final Duration base;
    private final Duration teto;
    private final double jitterFator;
    private final Random random;

    public PoliticaRetryEntrega(Duration base, Duration teto, double jitterFator, Random random) {
        if (base == null || base.isNegative() || base.isZero()) {
            throw new IllegalArgumentException("base deve ser positiva");
        }
        if (teto == null || teto.compareTo(base) < 0) {
            throw new IllegalArgumentException("teto deve ser maior ou igual a base");
        }
        if (jitterFator < 0 || jitterFator > 1) {
            throw new IllegalArgumentException("jitterFator deve estar entre 0 e 1: " + jitterFator);
        }
        this.base = base;
        this.teto = teto;
        this.jitterFator = jitterFator;
        this.random = Objects.requireNonNull(random, "random e obrigatorio");
    }

    /**
     * Espera antes da PROXIMA tentativa, dado o numero da tentativa que acabou de falhar de forma
     * transitoria ({@code tentativaConcluida >= 1}). Exponencial de base 2, capado no teto, com
     * jitter uniforme de {@code +-jitterFator}.
     */
    public Duration calcularEspera(int tentativaConcluida) {
        if (tentativaConcluida < 1) {
            throw new IllegalArgumentException("tentativaConcluida deve ser >= 1: " + tentativaConcluida);
        }

        // Expoente capado: tentativaConcluida vem de outbox_entrega.tentativas (INT), nunca grande
        // o bastante para overflow de long em uso real -- o cap e so defensivo.
        int expoente = Math.min(tentativaConcluida - 1, 32);
        long semJitterMillis = Math.min(base.toMillis() * (1L << expoente), teto.toMillis());

        double fatorJitter = 1.0 + (random.nextDouble() * 2 - 1) * jitterFator;
        long comJitterMillis = Math.round(semJitterMillis * fatorJitter);

        return Duration.ofMillis(Math.max(0, comJitterMillis));
    }
}
