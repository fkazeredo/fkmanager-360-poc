package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Corpo da instrucao de efetivacao (ADR-0005; plano #0004, secao 7): {@code idEft} e {@code idCor}
 * carregam UUID por extenso -- identidade de negocio, nao formato host-centric numerico --
 * enquanto {@code numCta} e as duas parcelas monetarias seguem o padrao host de zero-padding.
 */
public record EfetivacaoLegadoRequest(
        @NotBlank String idEft,
        @NotBlank @Pattern(regexp = "\\d{10}") String numCta,
        @NotBlank @Pattern(regexp = "\\d{15}") String vlrLimChqEspEsp,
        @NotBlank @Pattern(regexp = "\\d{15}") String vlrLimNov,
        @NotBlank String idCor
) {
}
