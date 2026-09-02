package com.fkmanager360.credito.application.port.out;

/**
 * O tempo de resposta do CoreLegado esgotou. A borda traduz para 504.
 */
public class CoreLegadoTimeoutException extends RuntimeException {

    public CoreLegadoTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
