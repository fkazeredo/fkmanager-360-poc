package com.fkmanager360.credito.adapter.out.legacy;

/**
 * O contrato host-centric da resposta de consulta de status (#0006; ADR-0005): {@code vlrLimEft} so
 * vem preenchido quando {@code codRet} confirma sucesso; em branco nos demais casos.
 */
record ConsultaStatusEfetivacaoLegadoResponse(String codRet, String msgRet, String idEft, String numPrt, String vlrLimEft) {
}
