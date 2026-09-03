package com.fkmanager360.credito.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Os identificadores mais simples do ticket -- SolicitacaoId, EfetivacaoId, CorrelationId e
 * AtorId -- agrupados aqui porque a regra de cada um e a mesma classe de validacao (nao nulo /
 * nao vazio) que ContaIdTest e DadosCreditoCoreTest ja exercitam separadamente para tipos com
 * regra propria mais rica.
 */
class IdentificadoresValorTest {

    @Test
    void solicitacaoId_naoAceitaUuidNulo() {
        assertThatThrownBy(() -> new SolicitacaoId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void solicitacaoId_encapsulaOUuid() {
        UUID uuid = UUID.randomUUID();
        assertThat(new SolicitacaoId(uuid).valor()).isEqualTo(uuid);
    }

    @Test
    void efetivacaoId_naoAceitaUuidNulo() {
        assertThatThrownBy(() -> new EfetivacaoId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void efetivacaoId_naoEOMessageIdDoOutbox_saoTiposDistintos() {
        // EfetivacaoId e CorrelationId sao identidades de negocio distintas -- mesmo com o mesmo
        // UUID por baixo, os tipos Java nao se confundem (nao ha conversao implicita entre eles).
        UUID mesmoUuid = UUID.randomUUID();
        assertThat(new EfetivacaoId(mesmoUuid)).isNotEqualTo(new CorrelationId(mesmoUuid));
    }

    @Test
    void correlationId_naoAceitaUuidNulo() {
        assertThatThrownBy(() -> new CorrelationId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void atorId_naoAceitaValorNuloOuEmBranco() {
        assertThatThrownBy(() -> new AtorId(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AtorId("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void atorId_encapsulaOValor() {
        assertThat(new AtorId("gerente-123").valor()).isEqualTo("gerente-123");
    }
}
