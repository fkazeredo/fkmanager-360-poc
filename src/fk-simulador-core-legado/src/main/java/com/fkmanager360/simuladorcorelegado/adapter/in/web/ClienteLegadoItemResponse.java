package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import com.fkmanager360.simuladorcorelegado.domain.ClienteLegadoRecord;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Resultado por ocorrencia do lote. {@code codRet}/{@code msgRet} sao proprios do item: um lote
 * de 10 pode ter 9 sucessos e 1 "104 CLIENTE NAO ENCONTRADO" no mesmo HTTP 200 -- o status HTTP
 * nao carrega o resultado de negocio por item (ADR-0005). Campos mestres vem em branco quando
 * {@code codRet} nao e sucesso.
 */
public record ClienteLegadoItemResponse(
        @Schema(description = "Codigo host-centric do Cliente, 10 digitos com zero-padding.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String codCli,
        @Schema(description = "\"000\" sucesso; \"104\" cliente nao encontrado.", example = "000",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String codRet,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String msgRet,
        @Schema(description = "Vazio quando codRet nao e sucesso.", requiredMode = Schema.RequiredMode.REQUIRED)
        String nomCli,
        @Schema(description = "Vazio quando codRet nao e sucesso.", requiredMode = Schema.RequiredMode.REQUIRED)
        String numCpf,
        @Schema(description = "Vazio quando codRet nao e sucesso.", requiredMode = Schema.RequiredMode.REQUIRED)
        String sitCad,
        @Schema(description = "Data no formato yyyyMMdd. Vazio quando codRet nao e sucesso.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String datCad
) {

    public static final String SUCESSO = "000";
    public static final String CLIENTE_NAO_ENCONTRADO = "104";

    static ClienteLegadoItemResponse sucesso(ClienteLegadoRecord registro) {
        return new ClienteLegadoItemResponse(
                registro.codCli(), SUCESSO, "OPERACAO CONCLUIDA COM SUCESSO",
                registro.nomCli(), registro.numCpf(), registro.sitCad(), registro.datCad());
    }

    static ClienteLegadoItemResponse naoEncontrado(String codCli) {
        return new ClienteLegadoItemResponse(
                codCli, CLIENTE_NAO_ENCONTRADO, "CLIENTE NAO ENCONTRADO",
                "", "", "", "");
    }
}
