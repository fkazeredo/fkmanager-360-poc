package com.fkmanager360.carteiraclientes.aplicacao.portas;

/**
 * A consulta ao CoreLegado nao respondeu dentro do prazo. A borda traduz para {@code 504}.
 */
public class CoreLegadoTimeoutException extends RuntimeException {

    public CoreLegadoTimeoutException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
