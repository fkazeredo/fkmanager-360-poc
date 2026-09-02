package com.fkmanager360.carteiraclientes.aplicacao.portas;

/**
 * Falha transitoria de transporte ao consultar o CoreLegado -- conexao recusada, reset, ou 5xx do
 * host. A borda traduz para {@code 503} (ADR-0005: falha de transporte e resposta tecnica valida
 * com erro de negocio sao coisas distintas).
 */
public class CoreLegadoIndisponivelException extends RuntimeException {

    public CoreLegadoIndisponivelException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
