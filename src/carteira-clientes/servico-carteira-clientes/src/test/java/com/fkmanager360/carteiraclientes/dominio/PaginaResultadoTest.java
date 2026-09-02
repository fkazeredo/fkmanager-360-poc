package com.fkmanager360.carteiraclientes.dominio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaginaResultadoTest {

    @Test
    void totalPaginas_arredondaParaCima() {
        var pagina = new PaginaResultado<>(List.of("a", "b"), 0, 5, 7);
        assertThat(pagina.totalPaginas()).isEqualTo(2);
    }

    @Test
    void totalPaginas_exata_naoArredonda() {
        var pagina = new PaginaResultado<>(List.of("a"), 0, 5, 10);
        assertThat(pagina.totalPaginas()).isEqualTo(2);
    }

    @Test
    void mapear_preservaMetadadosDaPagina() {
        var original = new PaginaResultado<>(List.of(1, 2, 3), 1, 3, 9);
        var mapeada = original.mapear(i -> "item-" + i);

        assertThat(mapeada.itens()).containsExactly("item-1", "item-2", "item-3");
        assertThat(mapeada.pagina()).isEqualTo(1);
        assertThat(mapeada.tamanho()).isEqualTo(3);
        assertThat(mapeada.totalElementos()).isEqualTo(9);
    }
}
