package com.fkmanager360.credito.adapter.in.web;

/**
 * O header {@code Idempotency-Key} esta presente, mas nao e um UUID valido (plano #0003, Fase 0,
 * passo 1). Distinta de header AUSENTE ({@code MissingRequestHeaderException}, tratada por Spring
 * antes do metodo do controller ser chamado): a borda traduz esta para {@code 400} com o codigo
 * estavel {@code IDEMPOTENCY_KEY_INVALIDA}, e a ausencia para {@code IDEMPOTENCY_KEY_AUSENTE} --
 * dois codigos distintos para dois defeitos distintos.
 *
 * <p>Vive em {@code adapter.in.web}, e nao em {@code application.port.out}: o parsing estrutural
 * do header e responsabilidade exclusiva da borda web (Javadoc de
 * {@code RegistrarSolicitacaoAumentoLimite}), nunca do caso de uso, que ja recebe uma
 * {@link com.fkmanager360.credito.domain.IdempotencyKey} bem-formada.
 */
class IdempotencyKeyInvalidaException extends RuntimeException {

    IdempotencyKeyInvalidaException(String message) {
        super(message);
    }
}
