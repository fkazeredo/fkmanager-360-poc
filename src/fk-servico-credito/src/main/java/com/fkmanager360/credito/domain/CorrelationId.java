package com.fkmanager360.credito.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifica a jornada de negocio de uma SolicitacaoAumentoLimite, atravessando HTTP, Outbox,
 * callback e reconciliacao (CONTEXT.md raiz, secao "Comandos e eventos de negocio"). Nasce em
 * Credito no instante em que a solicitacao e efetivamente criada -- nunca na borda web -- e nao e
 * {@code traceId} (execucao tecnica, ADR-0017) nem {@link IdempotencyKey} (tentativa de
 * submissao).
 */
public record CorrelationId(UUID valor) {

    public CorrelationId {
        Objects.requireNonNull(valor, "CorrelationId nao pode ser nulo");
    }
}
