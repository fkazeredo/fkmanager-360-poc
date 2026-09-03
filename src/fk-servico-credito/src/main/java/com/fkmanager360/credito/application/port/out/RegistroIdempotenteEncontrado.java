package com.fkmanager360.credito.application.port.out;

import java.util.Objects;

/**
 * Ja existe um registro de idempotencia para ({@code originadorId}, {@code Idempotency-Key}) --
 * encontrado seja pelo pre-check (Fase 0, passo 5), seja pela releitura apos um conflito de TX1
 * (guardrail de concorrencia). Em ambos os casos a aplicacao reage identicamente, classificando o
 * {@code registro} com {@code ClassificadorIdempotencia}.
 */
public record RegistroIdempotenteEncontrado(RegistroIdempotencia registro) implements ResultadoRegistroSolicitacao {

    public RegistroIdempotenteEncontrado {
        Objects.requireNonNull(registro, "registro e obrigatorio");
    }
}
