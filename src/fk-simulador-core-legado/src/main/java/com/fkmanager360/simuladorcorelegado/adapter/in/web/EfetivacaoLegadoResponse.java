package com.fkmanager360.simuladorcorelegado.adapter.in.web;

/**
 * O contrato host-centric da resposta de efetivacao (ADR-0005; plano #0004, secao 7): seis
 * COD-RETs ficticios cobrindo aceite e as quatro classes de falha definitiva, mais o payload
 * incompativel do mesmo {@code idEft}. {@code numPrt} so vem preenchido no aceite.
 */
record EfetivacaoLegadoResponse(String codRet, String msgRet, String idEft, String numPrt, String idCor) {

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
