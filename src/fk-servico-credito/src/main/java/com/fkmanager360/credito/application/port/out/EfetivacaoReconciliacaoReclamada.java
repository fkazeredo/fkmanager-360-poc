package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.ProtocoloCore;
import com.fkmanager360.credito.domain.SolicitacaoId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Um ciclo de reconciliacao reclamado atomicamente por
 * {@link ReconciliacaoEfetivacaoPort#reclamarProxima} (#0006). Carrega o {@code claimId} -- o
 * fencing token que toda escrita de TX-B precisa apresentar de volta -- e o suficiente para decidir
 * COMO consultar o Core: {@code protocoloConhecido} presente consulta por {@link ProtocoloCore};
 * ausente consulta por {@link EfetivacaoId} (aceite perdido, ADR-0009, emenda). {@code jaIndeterminada}
 * distingue a fase de polling de baixa frequencia (spec, secao "Reconciliacao") da fase normal de
 * backoff curto; {@code janelaExpiraEm} e o limite da janela normal usado so na fase ainda nao
 * indeterminada.
 */
public record EfetivacaoReconciliacaoReclamada(
        UUID claimId,
        EfetivacaoId efetivacaoId,
        SolicitacaoId solicitacaoId,
        Optional<ProtocoloCore> protocoloConhecido,
        boolean jaIndeterminada,
        Instant janelaExpiraEm,
        int tentativaAtual) {

    public EfetivacaoReconciliacaoReclamada {
        Objects.requireNonNull(claimId, "claimId e obrigatorio");
        Objects.requireNonNull(efetivacaoId, "efetivacaoId e obrigatorio");
        Objects.requireNonNull(solicitacaoId, "solicitacaoId e obrigatorio");
        Objects.requireNonNull(protocoloConhecido, "protocoloConhecido e obrigatorio");
        Objects.requireNonNull(janelaExpiraEm, "janelaExpiraEm e obrigatorio");
        if (tentativaAtual < 1) {
            throw new IllegalArgumentException("tentativaAtual deve ser >= 1: " + tentativaAtual);
        }
    }
}
