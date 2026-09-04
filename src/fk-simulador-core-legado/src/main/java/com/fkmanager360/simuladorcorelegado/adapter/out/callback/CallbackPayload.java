package com.fkmanager360.simuladorcorelegado.adapter.out.callback;

/**
 * Corpo do callback enviado ao {@code servico-credito} (espelha {@code CallbackEfetivacaoRequest}
 * do lado de la -- ADR-0005, mesmos nomes host-centric). Este simulador so emite sucesso: a
 * validacao (conta, situacao, limite vigente) ja aconteceu sincronamente no aceite da instrucao
 * (#0004) -- um retorno definitivo descoberto so depois nao existe neste simulador.
 */
record CallbackPayload(String idEft, String numPrt, String codRet, String vlrLimEft, String idCor) {

    private static final String COD_RET_SUCESSO = "000";

    static CallbackPayload deSucesso(ConfirmacaoEfetivacao confirmacao) {
        return new CallbackPayload(
                confirmacao.idEft(), confirmacao.numPrt(), COD_RET_SUCESSO, confirmacao.vlrLimEft(), confirmacao.idCor());
    }
}
