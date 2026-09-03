package com.fkmanager360.credito.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Identidade da SolicitacaoAumentoLimite, gerada na persistencia de TX1 (plano #0003, Fase 1).
 */
public record SolicitacaoId(UUID valor) {

    public SolicitacaoId {
        Objects.requireNonNull(valor, "SolicitacaoId nao pode ser nulo");
    }
}
