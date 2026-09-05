package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Consulta de status por qualquer um dos dois identificadores (#0006; ADR-0009, emenda): exatamente
 * um dos dois campos e preenchido por chamada -- {@code numPrt} quando o {@code ProtocoloCore} e
 * conhecido, {@code idEft} quando o aceite se perdeu. Sem {@code @NotBlank} em nenhum dos dois
 * (ambos sao opcionais individualmente): a regra "exatamente um" e validada no controller, porque
 * Bean Validation nao expressa XOR entre dois campos de forma simples.
 */
public record ConsultaStatusEfetivacaoLegadoRequest(
        @Schema(description = "EfetivacaoId por extenso (UUID). Usado quando numPrt nao e conhecido.",
                example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        String idEft,
        @Schema(description = "ProtocoloCore, 12 digitos com zero-padding. Usado quando ja conhecido.",
                example = "000000000001")
        String numPrt) {
}
