package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.AtorId;
import com.fkmanager360.credito.domain.ClienteId;
import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.ContextoDecisaoCredito;
import com.fkmanager360.credito.domain.CorrelationId;
import com.fkmanager360.credito.domain.IdempotencyKey;
import com.fkmanager360.credito.domain.ManifestacaoCliente;
import com.fkmanager360.credito.domain.OrigemSolicitacao;

import java.time.Instant;
import java.util.Objects;

/**
 * Tudo que TX1 precisa persistir atomicamente (plano #0003, Fase 1): a SolicitacaoAumentoLimite em
 * {@code SOLICITADA}, o ContextoDecisaoCredito imutavel, e o registro de idempotencia associado.
 * Note a ausencia deliberada de um {@code SolicitacaoId} -- a identidade e gerada pelo adapter de
 * persistencia (proxima etapa) e devolvida em {@link SolicitacaoCriada}.
 */
public record NovaSolicitacaoAumentoLimite(
        ClienteId clienteId,
        ContaId contaId,
        AtorId originadorId,
        OrigemSolicitacao origemSolicitacao,
        ManifestacaoCliente manifestacaoCliente,
        ContextoDecisaoCredito contextoDecisaoCredito,
        CorrelationId correlationId,
        IdempotencyKey idempotencyKey,
        String fingerprint,
        Instant registradaEm) {

    public NovaSolicitacaoAumentoLimite {
        Objects.requireNonNull(clienteId, "clienteId e obrigatorio");
        Objects.requireNonNull(contaId, "contaId e obrigatorio");
        Objects.requireNonNull(originadorId, "originadorId e obrigatorio");
        Objects.requireNonNull(origemSolicitacao, "origemSolicitacao e obrigatoria");
        Objects.requireNonNull(manifestacaoCliente, "manifestacaoCliente e obrigatoria");
        Objects.requireNonNull(contextoDecisaoCredito, "contextoDecisaoCredito e obrigatorio");
        Objects.requireNonNull(correlationId, "correlationId e obrigatorio");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey e obrigatoria");
        Objects.requireNonNull(registradaEm, "registradaEm e obrigatorio");
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("fingerprint e obrigatorio");
        }
    }
}
