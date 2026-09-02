package com.fkmanager360.carteiraclientes.application.port.out;

/**
 * Falha transitoria de transporte ao consultar o CoreLegado -- conexao recusada, reset, ou 5xx do
 * host. A borda traduz para {@code 503} (ADR-0005: falha de transporte e resposta tecnica valida
 * com erro de negocio sao coisas distintas).
 */
public class CoreLegadoUnavailableException extends RuntimeException {

    public CoreLegadoUnavailableException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
