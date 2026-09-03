package com.fkmanager360.credito.application.port.out;

/**
 * A mesma {@code Idempotency-Key} foi reutilizada com um payload diferente (fingerprint diverge do
 * gravado). A borda traduz para {@code 422} com o codigo estavel
 * {@code IDEMPOTENCIA_FINGERPRINT_DIVERGENTE}. A operacao original referenciada pelo registro
 * permanece inteiramente inalterada -- esta excecao nunca produz efeito colateral.
 */
public class IdempotenciaFingerprintDivergenteException extends RuntimeException implements ErroDeAplicacaoComCodigo {

    public static final String CODIGO = "IDEMPOTENCIA_FINGERPRINT_DIVERGENTE";

    public IdempotenciaFingerprintDivergenteException(String message) {
        super(message);
    }

    @Override
    public String codigo() {
        return CODIGO;
    }
}
