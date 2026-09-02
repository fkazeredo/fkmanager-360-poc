package com.fkmanager360.carteiraclientes.domain;

/**
 * Pedido de paginacao. {@code page} e zero-based; {@code size} tem limite superior para que a
 * carteira continue utilizavel quando crescer, sem exigir isso do chamador (spec, User Story 7).
 */
public record Pagination(int page, int size) {

    public static final int MAX_SIZE = 100;
    public static final int DEFAULT_SIZE = 20;

    public Pagination {
        if (page < 0) {
            throw new IllegalArgumentException("page nao pode ser negativa: " + page);
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException(
                    "size deve estar entre 1 e " + MAX_SIZE + ": " + size);
        }
    }

    public static Pagination ofDefault() {
        return new Pagination(0, DEFAULT_SIZE);
    }

    public int offset() {
        return page * size;
    }
}
