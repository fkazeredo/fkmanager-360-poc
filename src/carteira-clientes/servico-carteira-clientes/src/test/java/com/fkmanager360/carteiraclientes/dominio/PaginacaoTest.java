package com.fkmanager360.carteiraclientes.dominio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PaginacaoTest {

    @Test
    void deslocamento_multiplicaPaginaPorTamanho() {
        assertThat(new Paginacao(2, 5).deslocamento()).isEqualTo(10);
        assertThat(new Paginacao(0, 20).deslocamento()).isZero();
    }

    @Test
    void pagina_negativa_e_rejeitada() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Paginacao(-1, 10));
    }

    @Test
    void tamanho_zero_ou_negativo_e_rejeitado() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Paginacao(0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new Paginacao(0, -5));
    }

    @Test
    void tamanho_acimaDoMaximo_e_rejeitado() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Paginacao(0, Paginacao.TAMANHO_MAXIMO + 1));
    }

    @Test
    void padrao_e_primeiraPagina_comTamanhoPadrao() {
        assertThat(Paginacao.padrao()).isEqualTo(new Paginacao(0, Paginacao.TAMANHO_PADRAO));
    }
}
