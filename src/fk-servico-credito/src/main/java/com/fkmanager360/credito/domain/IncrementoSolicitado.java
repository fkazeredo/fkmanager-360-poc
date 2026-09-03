package com.fkmanager360.credito.domain;

/**
 * Diferenca entre o LimiteSolicitado e o LimiteChequeEspecialVigente (CONTEXT.md de Credito). Um
 * dos dois eixos da AlcadaAprovacao. Sempre positivo -- o proprio calculo em
 * {@link ContextoDecisaoCredito#congelar} garante isso antes de este tipo existir.
 */
public record IncrementoSolicitado(long centavos) {

    public IncrementoSolicitado {
        if (centavos <= 0) {
            throw new IllegalArgumentException("IncrementoSolicitado deve ser positivo: " + centavos);
        }
    }
}
