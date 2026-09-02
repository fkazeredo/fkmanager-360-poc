package com.fkmanager360.carteiraclientes.domain;

/**
 * Identidade da {@code ContaCorrente} dentro deste contexto, no mesmo desenho de
 * {@link ClienteId}: vive sem zero-padding, e a traducao para o numero de conta host-centric e
 * responsabilidade exclusiva da ACL, na fronteira de saida (ADR-0004, ADR-0005).
 */
public record ContaId(String valor) {

    public ContaId {
        if (valor == null || !valor.matches("[0-9]{1,10}")) {
            throw new IllegalArgumentException("ContaId deve ser numerico, com ate 10 digitos: " + valor);
        }
    }
}
