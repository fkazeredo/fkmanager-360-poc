package com.fkmanager360.credito.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A peca minima de dominio que encapsula a invariante de transicao (ver Javadoc da classe).
 */
class SolicitacaoAumentoLimiteTest {

    @Test
    void criar_produzEstadoInicialSolicitada() {
        assertThat(SolicitacaoAumentoLimite.criar().status())
                .isEqualTo(StatusSolicitacaoAumentoLimite.SOLICITADA);
    }

    @Test
    void transicaoValida_produzNovoObjetoComOAlvo() {
        var solicitacao = SolicitacaoAumentoLimite.criar();
        var aguardando = solicitacao.transicionarPara(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);

        assertThat(aguardando.status()).isEqualTo(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        // O objeto original nao muda -- e imutavel.
        assertThat(solicitacao.status()).isEqualTo(StatusSolicitacaoAumentoLimite.SOLICITADA);
    }

    @Test
    void transicaoInvalida_lancaTransicaoInvalidaException() {
        var solicitacao = SolicitacaoAumentoLimite.criar();
        assertThatThrownBy(() -> solicitacao.transicionarPara(StatusSolicitacaoAumentoLimite.EFETIVADA))
                .isInstanceOf(TransicaoInvalidaException.class);
    }

    @Test
    void terminalNuncaEReescrito_mesmoTentandoTransicionarParaOutroTerminal() {
        var rejeitada = SolicitacaoAumentoLimite.criar()
                .transicionarPara(StatusSolicitacaoAumentoLimite.REJEITADA);

        assertThatThrownBy(() -> rejeitada.transicionarPara(StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO))
                .isInstanceOf(TransicaoInvalidaException.class);
    }

    @Test
    void statusNulo_eRecusadoNoConstrutor() {
        assertThatThrownBy(() -> new SolicitacaoAumentoLimite(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void fluxoCompletoDeAprovacao_ateEfetivada() {
        var efetivada = SolicitacaoAumentoLimite.criar()
                .transicionarPara(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO)
                .transicionarPara(StatusSolicitacaoAumentoLimite.EFETIVADA);

        assertThat(efetivada.status()).isEqualTo(StatusSolicitacaoAumentoLimite.EFETIVADA);
    }
}
