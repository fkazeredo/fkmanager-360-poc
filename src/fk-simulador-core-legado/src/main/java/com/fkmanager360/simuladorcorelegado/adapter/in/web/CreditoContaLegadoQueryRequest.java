package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Corpo da consulta dos dados de credito de uma conta: numero da conta, 10 digitos com
 * zero-padding.
 */
public record CreditoContaLegadoQueryRequest(
        @Schema(pattern = "^\\d{10}$", example = "0000010001", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Pattern(regexp = "^\\d{10}$")
        String numCta
) {
}
