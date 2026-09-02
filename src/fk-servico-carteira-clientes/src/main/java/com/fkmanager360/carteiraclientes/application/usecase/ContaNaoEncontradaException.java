package com.fkmanager360.carteiraclientes.application.usecase;

/**
 * A conta pedida nao esta entre as contas que o CoreLegado reconhece para aquele Cliente. A
 * borda traduz para 404.
 *
 * <p>Distinta de {@link DireitoDeAtendimentoAusenteException} de proposito: aqui o gerente tem
 * direito sobre o Cliente e a conta simplesmente nao e dele (ou nao existe). Responder 404 nesse
 * caso nao revela nada sobre contas de outros Clientes -- a pergunta so chega a ser feita ao
 * Core depois de a autorizacao sobre o Cliente ja ter passado.
 */
public class ContaNaoEncontradaException extends RuntimeException {

    public ContaNaoEncontradaException(String message) {
        super(message);
    }
}
