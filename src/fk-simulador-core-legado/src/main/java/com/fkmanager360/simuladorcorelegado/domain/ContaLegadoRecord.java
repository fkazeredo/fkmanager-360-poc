package com.fkmanager360.simuladorcorelegado.domain;

/**
 * Dado de ContaCorrente tal como o CoreLegado o mantem: representacao host-centric interna do
 * simulador (ADR-0005). {@code numCta} e {@code codCli} sao numeros de 10 digitos com
 * zero-padding, {@code codAge} tem 4; {@code sitCta} usa os codigos do host -- "01" regular,
 * "02" bloqueada, "03" encerrada; {@code vlrLimChqEsp} e o limite de cheque especial em centavos,
 * 15 digitos com zero-padding, sem separador; {@code codRscCrd} e a classificacao de risco de
 * credito basica do host -- "1" baixo, "2" medio, "3" alto; e {@code datAtuLim} e a data em que o
 * proprio host atualizou o limite, sempre {@code yyyyMMdd}.
 *
 * <p>Nenhum destes nomes ou formatos e exposto fora da fronteira HTTP deste simulador.
 */
public record ContaLegadoRecord(
        String numCta,
        String codAge,
        String codCli,
        String sitCta,
        String vlrLimChqEsp,
        String codRscCrd,
        String datAtuLim
) {
}
