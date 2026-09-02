package com.fkmanager360.credito.adapter.out.legacy;

/**
 * O contrato host-centric como ele e (ADR-0005): abreviacoes, codigos numericos, centavos com
 * zero-padding e data {@code yyyyMMdd}. Nenhum destes nomes existe fora deste pacote.
 */
record AclCreditoContaResponse(
        String codRet,
        String msgRet,
        String numCta,
        String vlrLimChqEsp,
        String sitCta,
        String codRscCrd,
        String datAtuLim) {
}
