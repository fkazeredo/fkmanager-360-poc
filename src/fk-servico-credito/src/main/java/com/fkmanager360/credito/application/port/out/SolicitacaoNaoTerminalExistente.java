package com.fkmanager360.credito.application.port.out;

/**
 * TX1 colidiu no indice de unicidade nao terminal por ContaCorrente, e a releitura do registro de
 * idempotencia apos o rollback confirmou que NAO existe registro para
 * ({@code originadorId}, {@code Idempotency-Key}) -- ou seja, o conflito e mesmo uma segunda
 * solicitacao concorrente para a mesma conta, e nao um caso de idempotencia disfarcado (plano
 * #0003, "Classificacao apos rollback -- a idempotencia tem precedencia").
 */
public record SolicitacaoNaoTerminalExistente() implements ResultadoRegistroSolicitacao {
}
