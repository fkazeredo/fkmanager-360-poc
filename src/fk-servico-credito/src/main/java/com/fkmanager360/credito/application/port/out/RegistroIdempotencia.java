package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.AtorId;
import com.fkmanager360.credito.domain.IdempotencyKey;
import com.fkmanager360.credito.domain.SolicitacaoId;

import java.time.Instant;
import java.util.Objects;

/**
 * Registro de idempotencia por ({@code originadorId}, {@link IdempotencyKey}) -- mecanismo tecnico
 * de submissao, e nao vocabulario do glossario de Credito. Escrito uma unica vez, dentro de TX1
 * (plano #0003, Fase 1), e nunca atualizado depois.
 */
public record RegistroIdempotencia(
        AtorId originadorId,
        IdempotencyKey idempotencyKey,
        String fingerprint,
        SolicitacaoId solicitacaoId,
        Instant criadoEm) {

    public RegistroIdempotencia {
        Objects.requireNonNull(originadorId, "originadorId e obrigatorio");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey e obrigatoria");
        Objects.requireNonNull(solicitacaoId, "solicitacaoId e obrigatoria");
        Objects.requireNonNull(criadoEm, "criadoEm e obrigatorio");
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("fingerprint e obrigatorio");
        }
    }
}
