package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import com.fkmanager360.simuladorcorelegado.domain.ContaLegadoRecord;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ocorrencia da consulta de contas. Carrega apenas a identificacao da conta: situacao, limite e
 * risco pertencem a consulta de credito, que e outra capacidade e outro consumidor.
 */
public record ContaLegadoItemResponse(
        @Schema(description = "Numero da conta no host, 10 digitos com zero-padding.", example = "0000010001",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String numCta,
        @Schema(description = "Codigo da agencia, 4 digitos. Em branco e campo opcional ausente.", example = "0001",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String codAge) {

    static ContaLegadoItemResponse de(ContaLegadoRecord registro) {
        return new ContaLegadoItemResponse(registro.numCta(), registro.codAge());
    }
}
