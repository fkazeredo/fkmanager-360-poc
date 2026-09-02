package com.fkmanager360.carteiraclientes.domain;

import java.util.List;

/**
 * Resultado paginado, independente de framework -- o dominio nao conhece {@code Pageable} do
 * Spring Data (ADR-0020).
 */
public record PageResult<T>(List<T> items, int page, int size, long totalElements) {

    public long totalPages() {
        if (size <= 0) {
            return 0;
        }
        return (totalElements + size - 1) / size;
    }

    public <R> PageResult<R> map(java.util.function.Function<T, R> mapper) {
        return new PageResult<>(items.stream().map(mapper).toList(), page, size, totalElements);
    }
}
