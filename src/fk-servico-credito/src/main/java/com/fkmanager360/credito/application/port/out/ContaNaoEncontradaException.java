package com.fkmanager360.credito.application.port.out;

/**
 * A conta nao existe para quem foi perguntado -- seja porque CarteiraClientes nao a reconhece
 * para aquele Cliente, seja porque o CoreLegado nao a conhece. A borda traduz para 404.
 */
public class ContaNaoEncontradaException extends RuntimeException {

    public ContaNaoEncontradaException(String message) {
        super(message);
    }
}
