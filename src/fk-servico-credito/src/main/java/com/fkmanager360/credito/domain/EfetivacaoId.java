package com.fkmanager360.credito.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Identidade de negocio da tentativa logica de EfetivacaoLimite (CONTEXT.md de Credito), criada
 * quando a intencao e registrada duravelmente (TX2) e estavel por toda a vida dela, inclusive
 * atraves de reenvios. Nao e o {@code messageId} do Outbox, que e tecnico e pode mudar a cada
 * reenvio (ADR-0009 e sua emenda).
 */
public record EfetivacaoId(UUID valor) {

    public EfetivacaoId {
        Objects.requireNonNull(valor, "EfetivacaoId nao pode ser nulo");
    }
}
