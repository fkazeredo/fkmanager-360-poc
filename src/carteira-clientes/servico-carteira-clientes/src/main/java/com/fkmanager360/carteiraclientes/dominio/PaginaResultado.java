package com.fkmanager360.carteiraclientes.dominio;

import java.util.List;

/**
 * Resultado paginado, independente de framework -- o dominio nao conhece {@code Pageable} do
 * Spring Data (ADR-0020).
 */
public record PaginaResultado<T>(List<T> itens, int pagina, int tamanho, long totalElementos) {

    public long totalPaginas() {
        if (tamanho <= 0) {
            return 0;
        }
        return (totalElementos + tamanho - 1) / tamanho;
    }

    public <R> PaginaResultado<R> mapear(java.util.function.Function<T, R> mapeador) {
        return new PaginaResultado<>(itens.stream().map(mapeador).toList(), pagina, tamanho, totalElementos);
    }
}
