package com.fkmanager360.carteiraclientes.application.port.out;

/**
 * O CoreLegado respondeu, mas a resposta nao e semanticamente valida segundo o contrato que a ACL
 * conhece -- payload malformado, campo obrigatorio ausente, ou {@code COD-RET} desconhecido. A
 * borda traduz para {@code 502}. Nenhum destes detalhes atravessa para o domino (ADR-0005).
 */
public class InvalidCoreLegadoResponseException extends RuntimeException {

    public InvalidCoreLegadoResponseException(String mensagem) {
        super(mensagem);
    }

    public InvalidCoreLegadoResponseException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
