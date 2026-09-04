package com.fkmanager360.credito.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Indicacao tecnica do desfecho do callback (spec, secao "Callback"): o status HTTP ja carrega a
 * semantica principal (200/404/400), este corpo so distingue os quatro sub-casos de 200 para
 * diagnostico -- nunca para decisao de negocio do chamador (o CoreLegado nao reage a este corpo,
 * so ao status).
 */
record CallbackEfetivacaoResponse(
        @Schema(description = "PROCESSADO (concluiu agora); JA_CONCLUIDA (duplicado identico, AC13); "
                + "CONFLITO_REGISTRADO (contraditorio sobre terminal, AC17); ANOMALIA_REGISTRADA (sucesso "
                + "incoerente AC26, protocolo divergente, ou codRet desconhecido) -- todos 200.")
        String resultado,
        @Schema(description = "Detalhe tecnico opcional, nunca destinado a decisao do chamador.", nullable = true)
        String detalhe) {

    static final String PROCESSADO = "PROCESSADO";
    static final String JA_CONCLUIDA = "JA_CONCLUIDA";
    static final String CONFLITO_REGISTRADO = "CONFLITO_REGISTRADO";
    static final String ANOMALIA_REGISTRADA = "ANOMALIA_REGISTRADA";

    static CallbackEfetivacaoResponse processado() {
        return new CallbackEfetivacaoResponse(PROCESSADO, null);
    }

    static CallbackEfetivacaoResponse jaConcluida() {
        return new CallbackEfetivacaoResponse(JA_CONCLUIDA, null);
    }

    static CallbackEfetivacaoResponse conflitoRegistrado(String detalhe) {
        return new CallbackEfetivacaoResponse(CONFLITO_REGISTRADO, detalhe);
    }

    static CallbackEfetivacaoResponse anomaliaRegistrada(String detalhe) {
        return new CallbackEfetivacaoResponse(ANOMALIA_REGISTRADA, detalhe);
    }
}
