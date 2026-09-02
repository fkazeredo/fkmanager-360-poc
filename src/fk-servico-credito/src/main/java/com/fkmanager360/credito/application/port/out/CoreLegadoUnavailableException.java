package com.fkmanager360.credito.application.port.out;

/**
 * O CoreLegado esta inalcancavel ou respondeu erro de servidor: indisponibilidade transitoria,
 * nao resposta de negocio. A borda traduz para 503.
 */
public class CoreLegadoUnavailableException extends RuntimeException {

    public CoreLegadoUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
