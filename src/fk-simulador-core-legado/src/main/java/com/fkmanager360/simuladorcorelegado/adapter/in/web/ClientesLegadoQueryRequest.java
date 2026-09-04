package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Corpo da consulta em lote. {@code codCli} e sempre um numero de 10 digitos com zero-padding --
 * formato host -- e o lote respeita uma quantidade maxima de ocorrencias, ao estilo de um
 * {@code OCCURS} fictício desta POC (nao uma replica de copybook COBOL real).
 */
public record ClientesLegadoQueryRequest(
        @ArraySchema(
                arraySchema = @Schema(
                        description = "Codigos host do Cliente a consultar em lote, 10 digitos com zero-padding cada.",
                        example = "[\"0000000001\", \"0000000002\"]",
                        requiredMode = Schema.RequiredMode.REQUIRED),
                schema = @Schema(pattern = "^\\d{10}$"),
                minItems = 1,
                maxItems = ClientesLegadoQueryRequest.MAX_OCORRENCIAS)
        @NotEmpty
        @Size(min = 1, max = ClientesLegadoQueryRequest.MAX_OCORRENCIAS)
        List<@Pattern(regexp = "^\\d{10}$") String> codCli
) {
    public static final int MAX_OCORRENCIAS = 50;
}
