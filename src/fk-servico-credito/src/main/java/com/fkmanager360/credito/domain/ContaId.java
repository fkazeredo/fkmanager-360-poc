package com.fkmanager360.credito.domain;

/**
 * Identidade da ContaCorrente sobre a qual Credito opera. Vive sem zero-padding; a traducao para
 * o numero de conta host-centric e responsabilidade exclusiva da ACL deste contexto (ADR-0005).
 *
 * <p>O construtor canonicaliza (remove zero-padding a esquerda) em vez de apenas validar, pelo
 * mesmo motivo documentado na copia deste record em {@code CarteiraClientes}: sem isso,
 * {@code ContaId("10001")} e {@code ContaId("0000010001")} seriam identidades diferentes para a
 * mesma conta, e os dois contextos poderiam discordar sobre se um determinado numero de conta
 * "existe" dependendo de qual representacao chegou na requisicao.
 */
public record ContaId(String valor) {

    public ContaId {
        if (valor == null || !valor.matches("[0-9]{1,10}")) {
            throw new IllegalArgumentException("ContaId deve ser numerico, com ate 10 digitos: " + valor);
        }
        valor = canonicalizar(valor);
    }

    private static String canonicalizar(String valor) {
        String semZeros = valor.replaceFirst("^0+(?=\\d)", "");
        return semZeros.isEmpty() ? "0" : semZeros;
    }
}
