package com.fkmanager360.credito.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Corpo do callback de confirmacao da efetivacao (ADR-0005; spec, secao "Callback"; ticket #0005).
 * Host-centric como a instrucao de saida ({@code EfetivacaoLegadoRequest}): {@code idEft} carrega
 * UUID por extenso -- a chave de correlacao funcional -- e {@code numPrt} e sempre obrigatorio,
 * porque todo callback pressupoe um aceite previo que ja atribuiu protocolo (um retorno definitivo
 * descoberto ja no aceite nunca gera callback -- #0004 conclui isso sincronamente). {@code idCor}
 * pode ser ecoado como metadado, mas nunca e chave de negocio nem campo autoritativo (Credito
 * recupera o seu proprio {@code correlationId} pelo {@code EfetivacaoId}).
 *
 * <p>Sem anotacoes de Bean Validation, de proposito: este modulo nao depende de
 * {@code spring-boot-starter-validation} (mesma decisao de {@code SolicitacaoAumentoLimiteRequest}
 * -- validacao estrutural/semantica e responsabilidade explicita do controller e do caso de uso,
 * nunca de {@code @Valid}).
 */
public record CallbackEfetivacaoRequest(
        @Schema(description = "EfetivacaoId por extenso (UUID) -- chave de correlacao funcional do callback.",
                example = "3fa85f64-5717-4562-b3fc-2c963f66afa6", requiredMode = Schema.RequiredMode.REQUIRED)
        String idEft,
        @Schema(description = "ProtocoloCore -- sempre presente, porque todo callback pressupoe aceite previo.",
                example = "000000000001", requiredMode = Schema.RequiredMode.REQUIRED)
        String numPrt,
        @Schema(description = "\"000\" sucesso; \"121\" conta inexistente; \"118\" conta bloqueada; "
                + "\"205\" limite vigente divergente; \"199\" instrucao invalida; qualquer outro valor e "
                + "codigo desconhecido (anomalia registrada, nao conclui nada).",
                example = "000", requiredMode = Schema.RequiredMode.REQUIRED)
        String codRet,
        @Schema(description = "LimiteEfetivado. Obrigatorio quando codRet=\"000\"; ausente nos demais casos. "
                + "Centavos, sem separador.", example = "600000")
        String vlrLimEft,
        @Schema(description = "Instante de processamento no Core, quando disponivel. Metadado, nao autoritativo.")
        String dtaPrc,
        @Schema(description = "CorrelationId por extenso (UUID), ecoado como metadado. Nunca chave de negocio "
                + "nem campo autoritativo -- Credito recupera o seu proprio pelo EfetivacaoId.",
                example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
        String idCor) {
}
