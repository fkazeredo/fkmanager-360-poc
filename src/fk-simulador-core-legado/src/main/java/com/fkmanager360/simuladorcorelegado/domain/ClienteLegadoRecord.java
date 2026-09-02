package com.fkmanager360.simuladorcorelegado.domain;

/**
 * Dado mestre do Cliente tal como o CoreLegado o mantem: representacao host-centric interna do
 * simulador (ADR-0005). {@code sitCad} usa os codigos do host -- "01" ATIVO, "02" INATIVO, "03"
 * BLOQUEADO -- e {@code datCad} e sempre {@code yyyyMMdd}. Nenhum destes nomes ou formatos e
 * exposto fora da fronteira HTTP deste simulador.
 */
public record ClienteLegadoRecord(
        String codCli,
        String nomCli,
        String numCpf,
        String sitCad,
        String datCad
) {
}
