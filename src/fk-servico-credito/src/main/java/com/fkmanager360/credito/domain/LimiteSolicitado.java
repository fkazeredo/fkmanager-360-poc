package com.fkmanager360.credito.domain;

/**
 * Valor pretendido em uma SolicitacaoAumentoLimite (CONTEXT.md de Credito). Nao se torna
 * LimiteChequeEspecialVigente antes da EfetivacaoLimite -- este tipo carrega so o valor
 * pretendido, nunca o vigente.
 *
 * <p>Em centavos, como inteiro: dinheiro nao usa ponto flutuante em nenhuma camada (ADR-0005).
 */
public record LimiteSolicitado(long centavos) {

    public LimiteSolicitado {
        if (centavos <= 0) {
            throw new IllegalArgumentException("LimiteSolicitado deve ser positivo: " + centavos);
        }
    }
}
