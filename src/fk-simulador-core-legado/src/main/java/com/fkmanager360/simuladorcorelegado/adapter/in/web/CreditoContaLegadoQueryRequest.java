package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Corpo da consulta dos dados de credito de uma conta: numero da conta, 10 digitos com
 * zero-padding.
 */
public record CreditoContaLegadoQueryRequest(
        @NotBlank
        @Pattern(regexp = "\\d{10}")
        String numCta
) {
}
