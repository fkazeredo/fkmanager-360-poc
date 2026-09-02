package com.fkmanager360.carteiraclientes.domain;

/**
 * Identidade do {@code GerenteRelacionamento} autenticado. Neste slice, deriva diretamente do
 * claim {@code sub} do token validado na borda -- o dominio nao conhece JWT nem Spring Security
 * (ADR-0007): a fronteira converte a identidade autenticada num conceito de aplicacao antes de
 * chegar aqui.
 */
public record GerenteId(String valor) {

    public GerenteId {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("GerenteId nao pode ser vazio");
        }
    }
}
