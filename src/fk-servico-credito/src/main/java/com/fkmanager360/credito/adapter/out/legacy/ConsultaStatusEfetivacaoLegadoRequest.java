package com.fkmanager360.credito.adapter.out.legacy;

/**
 * Consulta de status por qualquer um dos dois identificadores (#0006; ADR-0009, emenda): {@code numPrt}
 * quando o {@code ProtocoloCore} e conhecido, {@code idEft} quando o aceite se perdeu. Exatamente
 * um dos dois e preenchido por chamada -- nunca os dois, nunca nenhum.
 */
record ConsultaStatusEfetivacaoLegadoRequest(String idEft, String numPrt) {
}
