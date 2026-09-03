package com.fkmanager360.credito.domain;

/**
 * Versao nomeada da PoliticaCredito (ex.: {@code "v1"}). Toda DecisaoCredito registra sob qual
 * versao foi tomada (CONTEXT.md de Credito).
 *
 * <p><b>Uma versao publicada e semanticamente imutavel.</b> Qualquer alteracao de comportamento de
 * uma politica ja publicada exige uma NOVA versao -- nunca a mutacao do comportamento associado a
 * uma versao ja existente. E essa garantia que permite a uma DecisaoCredito antiga continuar
 * explicavel com os mesmos fatos e a mesma versao registrados seis meses depois (ADR-0006), mesmo
 * que a politica vigente para novas solicitacoes tenha evoluido nesse meio tempo.
 */
public record VersaoPoliticaCredito(String valor) {

    public VersaoPoliticaCredito {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("VersaoPoliticaCredito nao pode ser vazia");
        }
    }
}
