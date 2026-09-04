package com.fkmanager360.simuladorcorelegado.adapter.out.callback;

/**
 * O suficiente para montar o callback de sucesso (#0005): {@code idEft} e a chave de correlacao
 * funcional; {@code numPrt} sempre presente, porque todo callback pressupoe aceite previo;
 * {@code vlrLimEft} e o novo limite ja aplicado em {@code ContasLegadoStore}; {@code idCor} e
 * ecoado como metadado.
 */
public record ConfirmacaoEfetivacao(String idEft, String numPrt, String vlrLimEft, String idCor) {
}
