package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Corpo da instrucao de efetivacao (ADR-0005; plano #0004, secao 7): {@code idEft} e {@code idCor}
 * carregam UUID por extenso -- identidade de negocio, nao formato host-centric numerico --
 * enquanto {@code numCta} e as duas parcelas monetarias seguem o padrao host de zero-padding.
 */
public record EfetivacaoLegadoRequest(
        @Schema(description = "EfetivacaoId por extenso (UUID) -- identidade de negocio da tentativa, "
                + "estavel atraves de reenvios.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String idEft,
        @Schema(pattern = "^\\d{10}$", example = "0000010001", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Pattern(regexp = "^\\d{10}$") String numCta,
        @Schema(description = "LimiteChequeEspecialVigenteEsperado -- a precondicao congelada no "
                + "ContextoDecisaoCredito. Centavos, 15 digitos com zero-padding, sem separador.",
                pattern = "^\\d{15}$", example = "000000000500000", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Pattern(regexp = "^\\d{15}$") String vlrLimChqEspEsp,
        @Schema(description = "LimiteSolicitado. Centavos, 15 digitos com zero-padding, sem separador.",
                pattern = "^\\d{15}$", example = "000000000600000", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Pattern(regexp = "^\\d{15}$") String vlrLimNov,
        @Schema(description = "CorrelationId por extenso (UUID) -- metadado de correlacao, nunca chave de negocio.",
                example = "7c9e6679-7425-40de-944b-e07fc1f90ae7", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String idCor
) {
}
