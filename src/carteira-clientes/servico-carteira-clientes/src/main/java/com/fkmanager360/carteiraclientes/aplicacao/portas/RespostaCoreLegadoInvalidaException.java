package com.fkmanager360.carteiraclientes.aplicacao.portas;

/**
 * O CoreLegado respondeu, mas a resposta nao e semanticamente valida segundo o contrato que a ACL
 * conhece -- payload malformado, campo obrigatorio ausente, ou {@code COD-RET} desconhecido. A
 * borda traduz para {@code 502}. Nenhum destes detalhes atravessa para o domino (ADR-0005).
 */
public class RespostaCoreLegadoInvalidaException extends RuntimeException {

    public RespostaCoreLegadoInvalidaException(String mensagem) {
        super(mensagem);
    }

    public RespostaCoreLegadoInvalidaException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
