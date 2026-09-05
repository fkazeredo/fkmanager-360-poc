package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * O contrato host-centric da resposta de consulta de status (#0006; ADR-0005): {@code vlrLimEft} so
 * vem preenchido quando {@code codRet} confirma processada; em branco nos demais casos.
 */
record ConsultaStatusEfetivacaoLegadoResponse(
        @Schema(description = "\"000\" processada; \"301\" aceita, ainda em processamento; \"404\" "
                + "efetivacao desconhecida (nenhum aceite com este identificador).",
                example = "000", requiredMode = Schema.RequiredMode.REQUIRED) String codRet,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String msgRet,
        @Schema(description = "Ecoado quando a consulta foi por idEft, ou resolvido a partir do numPrt.") String idEft,
        @Schema(description = "Ecoado quando a consulta foi por numPrt, ou resolvido a partir do idEft.") String numPrt,
        @Schema(description = "LimiteEfetivado. Presente somente quando codRet=\"000\".", nullable = true) String vlrLimEft) {

    static final String PROCESSADA = "000";
    static final String EM_PROCESSAMENTO = "301";
    static final String DESCONHECIDA = "404";

    static ConsultaStatusEfetivacaoLegadoResponse processada(String idEft, String numPrt, String vlrLimEft) {
        return new ConsultaStatusEfetivacaoLegadoResponse(PROCESSADA, "EFETIVACAO PROCESSADA", idEft, numPrt, vlrLimEft);
    }

    static ConsultaStatusEfetivacaoLegadoResponse emProcessamento(String idEft, String numPrt) {
        return new ConsultaStatusEfetivacaoLegadoResponse(EM_PROCESSAMENTO, "EFETIVACAO EM PROCESSAMENTO", idEft, numPrt, null);
    }

    static ConsultaStatusEfetivacaoLegadoResponse desconhecida(String idEftOuNull, String numPrtOuNull) {
        return new ConsultaStatusEfetivacaoLegadoResponse(DESCONHECIDA, "EFETIVACAO DESCONHECIDA", idEftOuNull, numPrtOuNull, null);
    }
}
