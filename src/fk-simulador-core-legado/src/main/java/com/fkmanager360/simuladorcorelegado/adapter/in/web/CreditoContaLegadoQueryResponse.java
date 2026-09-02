package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import com.fkmanager360.simuladorcorelegado.domain.ContaLegadoRecord;

/**
 * Os fatos de credito que o host mantem para a conta, numa unica resposta: limite de cheque
 * especial em centavos com zero-padding, situacao da conta, classificacao de risco de credito
 * basica e a data em que o proprio host atualizou o limite (ADR-0005).
 *
 * <p>{@code datAtuLim} e informacao da fonte sobre a fonte -- quando o host mexeu no limite -- e
 * nao tem nada a ver com o instante em que alguem consultou. Campos vem em branco quando
 * {@code codRet} nao e sucesso.
 */
public record CreditoContaLegadoQueryResponse(
        String codRet,
        String msgRet,
        String numCta,
        String vlrLimChqEsp,
        String sitCta,
        String codRscCrd,
        String datAtuLim
) {

    static final String SUCESSO = "000";
    static final String CONTA_NAO_ENCONTRADA = "121";

    static CreditoContaLegadoQueryResponse de(ContaLegadoRecord registro) {
        return new CreditoContaLegadoQueryResponse(
                SUCESSO, "OPERACAO CONCLUIDA COM SUCESSO",
                registro.numCta(), registro.vlrLimChqEsp(), registro.sitCta(),
                registro.codRscCrd(), registro.datAtuLim());
    }

    static CreditoContaLegadoQueryResponse naoEncontrada(String numCta) {
        return new CreditoContaLegadoQueryResponse(
                CONTA_NAO_ENCONTRADA, "CONTA NAO ENCONTRADA", numCta, "", "", "", "");
    }
}
