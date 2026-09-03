package com.fkmanager360.credito.domain;

/**
 * Componente que executa uma operacao sem pessoa por tras, identificado por nome proprio --
 * {@code MOTOR_DECISAO_CREDITO}, {@code RECONCILIACAO_EFETIVACAO}, {@code CORE_LEGADO}, e outros
 * conforme os slices seguintes introduzirem (CONTEXT.md raiz, secao "Atores"). Existe para que
 * operacao automatica tenha autor real, e nunca um AtorHumano vazio.
 *
 * <p>Este ticket so precisa de {@link #MOTOR_DECISAO_CREDITO} -- o autor de toda DecisaoCredito
 * automatica (CONTEXT.md de Credito, secao "Historico funcional").
 */
public record AtorSistema(String nome) implements AtorOperacao {

    public static final AtorSistema MOTOR_DECISAO_CREDITO = new AtorSistema("MOTOR_DECISAO_CREDITO");

    public AtorSistema {
        if (nome == null || nome.isBlank()) {
            throw new IllegalArgumentException("nome do AtorSistema e obrigatorio");
        }
    }
}
