package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.ContextoDecisaoCredito;
import com.fkmanager360.credito.domain.CorrelationId;
import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;

import java.util.Objects;

/**
 * O suficiente para a Fase 2 (decisao, fora de transacao, plano #0003) decidir e montar a
 * intencao de efetivacao e a entrada de historico sem tocar rede: o status atual, o
 * ContextoDecisaoCredito ja congelado em TX1 (imutavel -- le-lo antes de TX2 nao corre risco de os
 * fatos mudarem), a ContaId (destino da IntencaoEfetivacao) e o CorrelationId da jornada.
 */
public record CargaParaDecisao(
        StatusSolicitacaoAumentoLimite status,
        ContextoDecisaoCredito contexto,
        ContaId contaId,
        CorrelationId correlationId) {

    public CargaParaDecisao {
        Objects.requireNonNull(status, "status e obrigatorio");
        Objects.requireNonNull(contexto, "contexto e obrigatorio");
        Objects.requireNonNull(contaId, "contaId e obrigatorio");
        Objects.requireNonNull(correlationId, "correlationId e obrigatorio");
    }
}
