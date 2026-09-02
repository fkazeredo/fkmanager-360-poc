package com.fkmanager360.simuladorcorelegado.cliente;

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
        @NotEmpty
        @Size(max = ClientesLegadoQueryRequest.MAX_OCORRENCIAS)
        List<@Pattern(regexp = "\\d{10}") String> codCli
) {
    public static final int MAX_OCORRENCIAS = 50;
}
