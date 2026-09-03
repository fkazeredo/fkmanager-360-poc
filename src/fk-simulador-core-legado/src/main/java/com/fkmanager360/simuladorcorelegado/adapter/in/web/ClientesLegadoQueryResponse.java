package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Envelope do lote. {@code codRet}/{@code msgRet} no nivel do lote referem-se ao processamento da
 * requisicao como um todo (sempre "000" quando a requisicao chega bem formada ate aqui -- as
 * falhas estruturais sao recusadas antes, por HTTP 400); o resultado por Cliente esta em
 * {@code clientes}.
 */
public record ClientesLegadoQueryResponse(
        @Schema(description = "\"000\" quando a requisicao chegou bem formada (falhas estruturais sao 400).",
                example = "000", requiredMode = Schema.RequiredMode.REQUIRED)
        String codRet,
        @Schema(example = "LOTE PROCESSADO", requiredMode = Schema.RequiredMode.REQUIRED)
        String msgRet,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<ClienteLegadoItemResponse> clientes
) {

    static ClientesLegadoQueryResponse processado(List<ClienteLegadoItemResponse> clientes) {
        return new ClientesLegadoQueryResponse("000", "LOTE PROCESSADO", clientes);
    }
}
