package com.fkmanager360.credito.domain;

/**
 * Identidade da ContaCorrente sobre a qual Credito opera. Vive sem zero-padding; a traducao para
 * o numero de conta host-centric e responsabilidade exclusiva da ACL deste contexto (ADR-0005).
 */
public record ContaId(String valor) {

    public ContaId {
        if (valor == null || !valor.matches("[0-9]{1,10}")) {
            throw new IllegalArgumentException("ContaId deve ser numerico, com ate 10 digitos: " + valor);
        }
    }
}
