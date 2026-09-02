package com.fkmanager360.carteiraclientes.domain;

/**
 * Identidade da {@code ContaCorrente} dentro deste contexto, no mesmo desenho de
 * {@link ClienteId}: vive sem zero-padding, e a traducao para o numero de conta host-centric e
 * responsabilidade exclusiva da ACL, na fronteira de saida (ADR-0004, ADR-0005).
 *
 * <p>O construtor canonicaliza (remove zero-padding a esquerda) em vez de apenas validar. Sem
 * isso, {@code ContaId("10001")} e {@code ContaId("0000010001")} seriam duas identidades
 * diferentes para a mesma conta segundo {@code equals}, e o mesmo numero de conta poderia
 * resolver em Credito e falhar em CarteiraClientes -- ou vice-versa -- dependendo de qual
 * representacao cada chamador escolheu enviar. A canonicalizacao vive aqui, no value object, e
 * nao em cada controller ou adapter que o constroi.
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
