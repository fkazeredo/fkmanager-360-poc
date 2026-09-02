package com.fkmanager360.carteiraclientes.adapter.out.legacy;

/**
 * Traducao de representacao entre os identificadores deste contexto e o formato host-centric:
 * numeros de 10 digitos com zero-padding para fora, sem padding para dentro (ADR-0005). Vive na
 * ACL porque e a unica fronteira que pode conhecer o formato do host.
 */
final class HostFormat {

    private HostFormat() {
    }

    static String toCodigoHost(String valor) {
        return "%010d".formatted(Long.parseLong(valor));
    }

    static String stripLeadingZeros(String codigoHost) {
        String semZeros = codigoHost.replaceFirst("^0+(?=\\d)", "");
        return semZeros.isEmpty() ? "0" : semZeros;
    }
}
