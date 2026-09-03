package com.fkmanager360.credito.application.port.out;

/**
 * O {@code limiteSolicitado} nao e estritamente maior que o {@code LimiteChequeEspecialVigente}
 * relido no CoreLegado (passo 7/10 da Fase 0 do plano #0003) -- e a comparacao ja foi feita contra
 * o vigente ATUAL, e nao contra o que o gerente viu (esse caso e
 * {@link LimiteVigenteDesatualizadoException}, avaliado antes). A borda traduz para {@code 422}
 * com o codigo estavel {@code LIMITE_SOLICITADO_NAO_AUMENTA}. Isto e comando invalido, e nunca
 * decisao de credito: nao persiste SolicitacaoAumentoLimite, nao gera DecisaoCredito, nao gera
 * Outbox, nao gera historico.
 */
public class LimiteSolicitadoNaoAumentaException extends RuntimeException implements ErroDeAplicacaoComCodigo {

    public static final String CODIGO = "LIMITE_SOLICITADO_NAO_AUMENTA";

    public LimiteSolicitadoNaoAumentaException(String message) {
        super(message);
    }

    @Override
    public String codigo() {
        return CODIGO;
    }
}
