package com.fkmanager360.credito.adapter.out.legacy;

/**
 * O contrato host-centric da resposta de efetivacao (ADR-0005): {@code numPrt} so vem preenchido
 * no aceite (COD-RET de sucesso); em branco nos demais casos.
 */
record EfetivacaoLegadoResponse(String codRet, String msgRet, String idEft, String numPrt, String idCor) {
}
