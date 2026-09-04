package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * O contrato host-centric da resposta de efetivacao (ADR-0005; plano #0004, secao 7): seis
 * COD-RETs ficticios cobrindo aceite e as quatro classes de falha definitiva, mais o payload
 * incompativel do mesmo {@code idEft}. {@code numPrt} so vem preenchido no aceite.
 */
record EfetivacaoLegadoResponse(
        @Schema(description = "\"000\" aceite; \"121\" conta nao encontrada; \"118\" conta bloqueada; "
                + "\"205\" limite vigente divergente; \"199\" instrucao invalida; \"207\" idEft ja existente "
                + "com payload incompativel; \"998\" indisponibilidade transitoria de negocio (nunca emitido "
                + "por este simulador -- ver descricao do 200 na operacao).",
                example = "000", requiredMode = Schema.RequiredMode.REQUIRED) String codRet,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String msgRet,
        @Schema(description = "Ecoado da requisicao.", requiredMode = Schema.RequiredMode.REQUIRED) String idEft,
        @Schema(description = "ProtocoloCore, 12 digitos com zero-padding. Presente somente no aceite (codRet \"000\").",
                example = "000000000001", nullable = true) String numPrt,
        @Schema(description = "Ecoado da requisicao.", requiredMode = Schema.RequiredMode.REQUIRED) String idCor) {

    static final String ACEITE = "000";
    static final String CONTA_NAO_ENCONTRADA = "121";
    static final String CONTA_BLOQUEADA = "118";
    static final String LIMITE_VIGENTE_DIVERGENTE = "205";
    static final String INSTRUCAO_INVALIDA = "199";
    static final String PAYLOAD_INCOMPATIVEL = "207";

    static EfetivacaoLegadoResponse aceite(EfetivacaoLegadoRequest requisicao, String numPrt) {
        return new EfetivacaoLegadoResponse(ACEITE, "EFETIVACAO ACEITA", requisicao.idEft(), numPrt, requisicao.idCor());
    }

    static EfetivacaoLegadoResponse contaNaoEncontrada(EfetivacaoLegadoRequest requisicao) {
        return new EfetivacaoLegadoResponse(CONTA_NAO_ENCONTRADA, "CONTA NAO ENCONTRADA", requisicao.idEft(), null, requisicao.idCor());
    }

    static EfetivacaoLegadoResponse contaBloqueada(EfetivacaoLegadoRequest requisicao) {
        return new EfetivacaoLegadoResponse(CONTA_BLOQUEADA, "CONTA BLOQUEADA", requisicao.idEft(), null, requisicao.idCor());
    }

    static EfetivacaoLegadoResponse limiteVigenteDivergente(EfetivacaoLegadoRequest requisicao) {
        return new EfetivacaoLegadoResponse(LIMITE_VIGENTE_DIVERGENTE, "LIMITE VIGENTE DIVERGENTE", requisicao.idEft(), null, requisicao.idCor());
    }

    static EfetivacaoLegadoResponse instrucaoInvalida(EfetivacaoLegadoRequest requisicao) {
        return new EfetivacaoLegadoResponse(INSTRUCAO_INVALIDA, "INSTRUCAO INVALIDA", requisicao.idEft(), null, requisicao.idCor());
    }

    static EfetivacaoLegadoResponse payloadIncompativel(EfetivacaoLegadoRequest requisicao) {
        return new EfetivacaoLegadoResponse(
                PAYLOAD_INCOMPATIVEL, "EFETIVACAO ID JA EXISTENTE COM PAYLOAD INCOMPATIVEL", requisicao.idEft(), null, requisicao.idCor());
    }
}
