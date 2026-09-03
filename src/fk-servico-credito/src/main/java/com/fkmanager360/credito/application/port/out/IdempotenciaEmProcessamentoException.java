package com.fkmanager360.credito.application.port.out;

/**
 * A mesma {@code Idempotency-Key} esta sendo processada agora, em outra requisicao concorrente
 * (plano #0003, tabela de idempotencia: {@code FOR UPDATE NOWAIT} recusado). A borda traduz para
 * {@code 409} com o codigo estavel {@code IDEMPOTENCIA_EM_PROCESSAMENTO}.
 *
 * <p>E lancada pelo adapter de persistencia (proxima etapa) ao tentar adquirir o lock exclusivo da
 * solicitacao em TX2 -- este ticket so define a excecao e o seu codigo, para que a taxonomia de
 * erro esteja completa desde a camada de aplicacao.
 */
public class IdempotenciaEmProcessamentoException extends RuntimeException implements ErroDeAplicacaoComCodigo {

    public static final String CODIGO = "IDEMPOTENCIA_EM_PROCESSAMENTO";

    public IdempotenciaEmProcessamentoException(String message) {
        super(message);
    }

    @Override
    public String codigo() {
        return CODIGO;
    }
}
