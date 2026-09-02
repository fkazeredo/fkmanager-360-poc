package com.fkmanager360.carteiraclientes.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PaginationTest {

    @Test
    void deslocamento_multiplicaPaginaPorTamanho() {
        assertThat(new Pagination(2, 5).offset()).isEqualTo(10);
        assertThat(new Pagination(0, 20).offset()).isZero();
    }

    @Test
    void pagina_negativa_e_rejeitada() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Pagination(-1, 10));
    }

    @Test
    void tamanho_zero_ou_negativo_e_rejeitado() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Pagination(0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new Pagination(0, -5));
    }

    @Test
    void tamanho_acimaDoMaximo_e_rejeitado() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Pagination(0, Pagination.MAX_SIZE + 1));
    }

    @Test
    void padrao_e_primeiraPagina_comTamanhoPadrao() {
        assertThat(Pagination.ofDefault()).isEqualTo(new Pagination(0, Pagination.DEFAULT_SIZE));
    }
}
