package com.fkmanager360.carteiraclientes.dominio;

/**
 * Identidade do {@code Cliente} dentro deste contexto. Vive sem zero-padding nem qualquer outro
 * artefato de representacao do host -- a traducao para o {@code COD-CLI} host-centric e
 * responsabilidade exclusiva da ACL, na fronteira de saida (ADR-0004, ADR-0005).
 */
public record ClienteId(String valor) {

    public ClienteId {
        if (valor == null || !valor.matches("[0-9]{1,10}")) {
            throw new IllegalArgumentException("ClienteId deve ser numerico, com ate 10 digitos: " + valor);
        }
    }
}
