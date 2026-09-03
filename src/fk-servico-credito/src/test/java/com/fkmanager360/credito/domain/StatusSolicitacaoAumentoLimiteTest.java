package com.fkmanager360.credito.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO;
import static com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite.EFETIVACAO_INDETERMINADA;
import static com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite.EFETIVADA;
import static com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO;
import static com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite.REJEITADA;
import static com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite.SOLICITADA;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tabela de transicoes EXATA da spec (secao "Maquina de estados"). Inclui transicoes que nenhum
 * caso de uso de #0003 alcanca -- elas sao regra da maquina, e precisam existir e ser recusadas
 * corretamente quando invalidas, mesmo sem caso de uso que as invoque ainda (ADR-0010, ADR-0018).
 */
class StatusSolicitacaoAumentoLimiteTest {

    @Test
    void naoTerminais_saoExatamenteOsTresDaSpec() {
        assertThat(SOLICITADA.isTerminal()).isFalse();
        assertThat(AGUARDANDO_EFETIVACAO.isTerminal()).isFalse();
        assertThat(EFETIVACAO_INDETERMINADA.isTerminal()).isFalse();
    }

    @Test
    void terminais_saoExatamenteOsTresDaSpec() {
        assertThat(EFETIVADA.isTerminal()).isTrue();
        assertThat(REJEITADA.isTerminal()).isTrue();
        assertThat(FALHA_EFETIVACAO.isTerminal()).isTrue();
    }

    @Test
    void solicitada_podeIrParaRejeitada_decisaoAutomaticaRecusa() {
        assertThat(SOLICITADA.podeTransicionarPara(REJEITADA)).isTrue();
    }

    @Test
    void solicitada_podeIrParaAguardandoEfetivacao_decisaoAutomaticaAprova() {
        assertThat(SOLICITADA.podeTransicionarPara(AGUARDANDO_EFETIVACAO)).isTrue();
    }

    @Test
    void solicitada_naoPodeIrParaEfetivadaDiretamente() {
        assertThat(SOLICITADA.podeTransicionarPara(EFETIVADA)).isFalse();
    }

    @Test
    void solicitada_naoPodeIrParaFalhaEfetivacaoDiretamente() {
        assertThat(SOLICITADA.podeTransicionarPara(FALHA_EFETIVACAO)).isFalse();
    }

    @Test
    void solicitada_naoPodeIrParaEfetivacaoIndeterminadaDiretamente() {
        assertThat(SOLICITADA.podeTransicionarPara(EFETIVACAO_INDETERMINADA)).isFalse();
    }

    @Test
    void aguardandoEfetivacao_podeIrParaEfetivada_resultadoAutoritativoDeSucesso() {
        assertThat(AGUARDANDO_EFETIVACAO.podeTransicionarPara(EFETIVADA)).isTrue();
    }

    @Test
    void aguardandoEfetivacao_podeIrParaFalhaEfetivacao_resultadoAutoritativoDeFalha() {
        assertThat(AGUARDANDO_EFETIVACAO.podeTransicionarPara(FALHA_EFETIVACAO)).isTrue();
    }

    @Test
    void aguardandoEfetivacao_podeIrParaEfetivacaoIndeterminada_janelaEsgotada() {
        assertThat(AGUARDANDO_EFETIVACAO.podeTransicionarPara(EFETIVACAO_INDETERMINADA)).isTrue();
    }

    @Test
    void aguardandoEfetivacao_naoPodeVoltarParaSolicitada() {
        assertThat(AGUARDANDO_EFETIVACAO.podeTransicionarPara(SOLICITADA)).isFalse();
    }

    @Test
    void aguardandoEfetivacao_naoPodeIrParaRejeitada() {
        assertThat(AGUARDANDO_EFETIVACAO.podeTransicionarPara(REJEITADA)).isFalse();
    }

    @Test
    void efetivacaoIndeterminada_podeIrParaEfetivada() {
        assertThat(EFETIVACAO_INDETERMINADA.podeTransicionarPara(EFETIVADA)).isTrue();
    }

    @Test
    void efetivacaoIndeterminada_podeIrParaFalhaEfetivacao() {
        assertThat(EFETIVACAO_INDETERMINADA.podeTransicionarPara(FALHA_EFETIVACAO)).isTrue();
    }

    @Test
    void efetivacaoIndeterminada_naoPodeVoltarParaAguardandoEfetivacao() {
        assertThat(EFETIVACAO_INDETERMINADA.podeTransicionarPara(AGUARDANDO_EFETIVACAO)).isFalse();
    }

    @Test
    void efetivacaoIndeterminada_naoPodeIrParaSolicitada() {
        assertThat(EFETIVACAO_INDETERMINADA.podeTransicionarPara(SOLICITADA)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = StatusSolicitacaoAumentoLimite.class, names = {"EFETIVADA", "REJEITADA", "FALHA_EFETIVACAO"})
    void nenhumTerminal_permiteQualquerTransicao(StatusSolicitacaoAumentoLimite terminal) {
        for (StatusSolicitacaoAumentoLimite alvo : StatusSolicitacaoAumentoLimite.values()) {
            assertThat(terminal.podeTransicionarPara(alvo))
                    .as(terminal + " -> " + alvo + " deveria ser sempre invalida (terminal nunca e reescrito)")
                    .isFalse();
        }
    }

    @Test
    void alvoNulo_eRecusado() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SOLICITADA.podeTransicionarPara(null))
                .isInstanceOf(NullPointerException.class);
    }
}
