package com.fkmanager360.credito.domain;

/**
 * Componente que executa uma operacao sem pessoa por tras, identificado por nome proprio --
 * {@code MOTOR_DECISAO_CREDITO}, {@code RECONCILIACAO_EFETIVACAO}, {@code CORE_LEGADO}, e outros
 * conforme os slices seguintes introduzirem (CONTEXT.md raiz, secao "Atores"). Existe para que
 * operacao automatica tenha autor real, e nunca um AtorHumano vazio.
 *
 * <p>#0003 so precisava de {@link #MOTOR_DECISAO_CREDITO}. #0004 acrescenta
 * {@link #CORE_LEGADO} -- autor do fato de historico que registra o aceite da instrucao de
 * efetivacao, e da conclusao definitiva quando o proprio Core devolve o resultado (CONTEXT.md
 * raiz, secao "Atores"; CONTEXT.md de Credito, secao "Historico funcional").
 */
public record AtorSistema(String nome) implements AtorOperacao {

    public static final AtorSistema MOTOR_DECISAO_CREDITO = new AtorSistema("MOTOR_DECISAO_CREDITO");
    public static final AtorSistema CORE_LEGADO = new AtorSistema("CORE_LEGADO");

    public AtorSistema {
        if (nome == null || nome.isBlank()) {
            throw new IllegalArgumentException("nome do AtorSistema e obrigatorio");
        }
    }
}
