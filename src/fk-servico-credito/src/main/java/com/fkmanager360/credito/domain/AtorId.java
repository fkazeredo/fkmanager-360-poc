package com.fkmanager360.credito.domain;

/**
 * Identidade do AtorHumano que originou ou opera sobre uma SolicitacaoAumentoLimite -- a claim
 * {@code sub} do JWT, ja traduzida pela borda para um conceito de aplicacao (ADR-0007: "o dominio
 * nao conhece JWT, OAuth nem Spring Security").
 */
public record AtorId(String valor) {

    public AtorId {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("AtorId nao pode ser vazio");
        }
    }
}
