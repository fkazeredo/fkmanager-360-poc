package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Corpo da consulta de contas de um Cliente. A chave e sempre o {@code codCli} -- numero de 10
 * digitos com zero-padding, formato host.
 *
 * <p>Nao existe consulta de conta por {@code numCta} neste contrato, e a ausencia e deliberada:
 * nenhum consumidor precisa descobrir o dono de uma conta antes de ja estar autorizado sobre o
 * Cliente, e oferecer essa chave conveniente conviteria exatamente a inversao de ordem que a
 * autorizacao de recurso proibe (ADR-0007).
 */
public record ContasLegadoQueryRequest(
        @NotBlank
        @Pattern(regexp = "\\d{10}")
        String codCli
) {
}
