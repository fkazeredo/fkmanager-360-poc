package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.SolicitacaoId;

import java.util.Objects;
import java.util.UUID;

/**
 * Um episodio de entrega reclamado atomicamente por {@link EntregasEfetivacaoPort#reclamarProxima}
 * (plano #0004, secao 4 -- claim unitario, um episodio por reclamacao). Carrega o
 * {@code claimId} -- o fencing token que TODA escrita de resultado (TX-B) precisa apresentar de
 * volta para que o efeito seja aplicado (decisao do Owner, OD-1) -- e {@code tentativaAtual}, o
 * numero do episodio que acabou de ser reservado, usado por {@code PoliticaRetryEntrega} para
 * calcular a proxima espera em caso de falha transitoria.
 */
public record EntregaEfetivacaoReclamada(
        UUID claimId,
        IntencaoEfetivacao intencao,
        SolicitacaoId solicitacaoId,
        int tentativaAtual) {

    public EntregaEfetivacaoReclamada {
        Objects.requireNonNull(claimId, "claimId e obrigatorio");
        Objects.requireNonNull(intencao, "intencao e obrigatoria");
        Objects.requireNonNull(solicitacaoId, "solicitacaoId e obrigatorio");
        if (tentativaAtual < 1) {
            throw new IllegalArgumentException("tentativaAtual deve ser >= 1: " + tentativaAtual);
        }
    }
}
