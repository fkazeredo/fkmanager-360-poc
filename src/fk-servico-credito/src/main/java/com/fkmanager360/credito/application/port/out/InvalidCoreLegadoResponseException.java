package com.fkmanager360.credito.application.port.out;

/**
 * O CoreLegado respondeu algo que a ACL nao sabe interpretar: COD-RET desconhecido, payload
 * malformado, campo obrigatorio ausente ou fora do formato do contrato. A borda traduz para 502.
 */
public class InvalidCoreLegadoResponseException extends RuntimeException {

    public InvalidCoreLegadoResponseException(String message) {
        super(message);
    }

    public InvalidCoreLegadoResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
