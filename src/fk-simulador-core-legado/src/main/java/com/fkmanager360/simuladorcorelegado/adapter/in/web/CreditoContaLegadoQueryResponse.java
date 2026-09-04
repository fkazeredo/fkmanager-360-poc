package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import com.fkmanager360.simuladorcorelegado.domain.ContaLegadoRecord;
import io.swagger.v3.oas.annotations.media.Schema;

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
        @Schema(description = "\"000\" sucesso; \"121\" conta nao encontrada.", example = "000",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String codRet,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String msgRet,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String numCta,
        @Schema(description = "Limite de cheque especial em centavos, 15 digitos com zero-padding e sem "
                + "separador. Vazio quando codRet nao e sucesso.", example = "000000000500000",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String vlrLimChqEsp,
        @Schema(description = "\"01\" regular, \"02\" bloqueada, \"03\" encerrada. Vazio quando codRet nao e sucesso.",
                example = "01", requiredMode = Schema.RequiredMode.REQUIRED)
        String sitCta,
        @Schema(description = "Classificacao de risco de credito basica do host: \"1\" baixo, \"2\" medio, "
                + "\"3\" alto. E insumo interno da politica e nunca e apresentada ao GerenteRelacionamento.",
                example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        String codRscCrd,
        @Schema(description = "Data em que o PROPRIO HOST atualizou o limite, no formato yyyyMMdd. Nao se "
                + "confunde com o instante em que a plataforma consultou -- sao conceitos distintos, e a "
                + "ACL de Credito nao deriva um do outro.", example = "20260115",
                requiredMode = Schema.RequiredMode.REQUIRED)
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
