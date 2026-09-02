package com.fkmanager360.carteiraclientes.dominio;

/**
 * Pedido de paginacao. {@code pagina} e zero-based; {@code tamanho} tem limite superior para que a
 * carteira continue utilizavel quando crescer, sem exigir isso do chamador (spec, User Story 7).
 */
public record Paginacao(int pagina, int tamanho) {

    public static final int TAMANHO_MAXIMO = 100;
    public static final int TAMANHO_PADRAO = 20;

    public Paginacao {
        if (pagina < 0) {
            throw new IllegalArgumentException("pagina nao pode ser negativa: " + pagina);
        }
        if (tamanho < 1 || tamanho > TAMANHO_MAXIMO) {
            throw new IllegalArgumentException(
                    "tamanho deve estar entre 1 e " + TAMANHO_MAXIMO + ": " + tamanho);
        }
    }

    public static Paginacao padrao() {
        return new Paginacao(0, TAMANHO_PADRAO);
    }

    public int deslocamento() {
        return pagina * tamanho;
    }
}
