package com.fkmanager360.credito.domain;

/**
 * Motivo estavel de {@code FALHA_EFETIVACAO} quando o CoreLegado devolve, na propria instrucao,
 * um retorno definitivo e autoritativo de que aquela efetivacao nao pode ser realizada (spec,
 * secao "Taxonomia de resultados na ACL"; ADR-0009 e sua emenda). Nunca produzido por falha de
 * transporte, timeout ou esgotamento de tentativas -- essas sao classes transitorias ou
 * indeterminadas, e nao alcancam este enum (invariante 8 do ticket #0004).
 *
 * <p>{@code LIMITE_VIGENTE_DIVERGENTE} e o unico motivo exigido explicitamente pela spec (AC15):
 * a precondicao de {@code limiteChequeEspecialVigenteEsperado} foi violada. Os demais cobrem os
 * outros retornos definitivos que o simulador precisa distinguir semanticamente (contrato do
 * simulador, spec).
 */
public enum MotivoFalhaEfetivacao {
    LIMITE_VIGENTE_DIVERGENTE,
    CONTA_INEXISTENTE,
    CONTA_BLOQUEADA_NA_EFETIVACAO,
    INSTRUCAO_INVALIDA
}
