package com.fkmanager360.credito.application.port.out;

/**
 * CarteiraClientes esta inalcancavel, lento demais ou respondeu de forma que este contexto nao
 * sabe interpretar. Distinta das excecoes do CoreLegado de proposito: sao dependencias
 * diferentes, e confundi-las esconderia qual delas caiu. A borda traduz para 503.
 */
public class CarteiraClientesUnavailableException extends RuntimeException {

    public CarteiraClientesUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
