package com.fkmanager360.carteiraclientes.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResultTest {

    @Test
    void totalPaginas_arredondaParaCima() {
        var pagina = new PageResult<>(List.of("a", "b"), 0, 5, 7);
        assertThat(pagina.totalPages()).isEqualTo(2);
    }

    @Test
    void totalPaginas_exata_naoArredonda() {
        var pagina = new PageResult<>(List.of("a"), 0, 5, 10);
        assertThat(pagina.totalPages()).isEqualTo(2);
    }

    @Test
    void mapear_preservaMetadadosDaPagina() {
        var original = new PageResult<>(List.of(1, 2, 3), 1, 3, 9);
        var mapeada = original.map(i -> "item-" + i);

        assertThat(mapeada.items()).containsExactly("item-1", "item-2", "item-3");
        assertThat(mapeada.page()).isEqualTo(1);
        assertThat(mapeada.size()).isEqualTo(3);
        assertThat(mapeada.totalElements()).isEqualTo(9);
    }
}
