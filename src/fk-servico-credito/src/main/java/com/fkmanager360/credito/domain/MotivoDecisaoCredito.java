package com.fkmanager360.credito.domain;

/**
 * Codigo estavel que diz por que a DecisaoCredito foi o que foi (CONTEXT.md de Credito). Cada
 * motivo carrega o seu {@link ResultadoDecisaoCredito} no proprio construtor do enum, e nao um
 * {@code switch} externo em outra classe -- assim resultado e motivo nunca podem divergir, porque
 * nao existe caminho de codigo que atribua um resultado a um motivo sem passar por aqui.
 */
public enum MotivoDecisaoCredito {

    CONTA_NAO_ELEGIVEL(ResultadoDecisaoCredito.REJEITADA),
    PERFIL_RISCO_INCOMPATIVEL(ResultadoDecisaoCredito.REJEITADA),
    DENTRO_DA_POLITICA_AUTOMATICA(ResultadoDecisaoCredito.APROVADA),
    FORA_DA_POLITICA_AUTOMATICA(ResultadoDecisaoCredito.REJEITADA);

    private final ResultadoDecisaoCredito resultado;

    MotivoDecisaoCredito(ResultadoDecisaoCredito resultado) {
        this.resultado = resultado;
    }

    public ResultadoDecisaoCredito resultado() {
        return resultado;
    }
}
