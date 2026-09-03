package com.fkmanager360.credito.domain;

/**
 * Uma transicao de {@link StatusSolicitacaoAumentoLimite} fora da tabela da spec foi tentada --
 * inclusive reescrever um estado terminal, que nunca e permitido (spec, secao "Maquina de
 * estados").
 */
public class TransicaoInvalidaException extends RuntimeException {

    public TransicaoInvalidaException(String message) {
        super(message);
    }
}
