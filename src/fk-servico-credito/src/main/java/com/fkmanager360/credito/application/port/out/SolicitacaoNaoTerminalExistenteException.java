package com.fkmanager360.credito.application.port.out;

/**
 * Ja existe uma SolicitacaoAumentoLimite nao terminal para a mesma ContaCorrente (spec, secao
 * "Unicidade nao terminal por ContaCorrente"). A borda traduz para {@code 409} com o codigo
 * estavel {@code SOLICITACAO_NAO_TERMINAL_EXISTENTE}.
 *
 * <p>So deve ser lancada quando {@link SolicitacoesAumentoLimitePort#registrar} devolver
 * {@link SolicitacaoNaoTerminalExistente} -- ou seja, depois que a releitura do registro de
 * idempotencia apos um eventual conflito de TX1 ja tiver confirmado que NAO se trata de um caso de
 * idempotencia disfarcado (guardrail de concorrencia documentado na porta).
 */
public class SolicitacaoNaoTerminalExistenteException extends RuntimeException implements ErroDeAplicacaoComCodigo {

    public static final String CODIGO = "SOLICITACAO_NAO_TERMINAL_EXISTENTE";

    public SolicitacaoNaoTerminalExistenteException(String message) {
        super(message);
    }

    @Override
    public String codigo() {
        return CODIGO;
    }
}
