package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.SolicitacaoId;

import java.util.Objects;

/** TX1 completou com sucesso: uma SolicitacaoAumentoLimite genuinamente nova foi criada. */
public record SolicitacaoCriada(SolicitacaoId id) implements ResultadoRegistroSolicitacao {

    public SolicitacaoCriada {
        Objects.requireNonNull(id, "id e obrigatorio");
    }
}
