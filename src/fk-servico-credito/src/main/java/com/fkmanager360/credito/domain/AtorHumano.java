package com.fkmanager360.credito.domain;

import java.util.Objects;

/**
 * Pessoa identificada exercendo um dos papeis reconhecidos pelo sistema (CONTEXT.md raiz). Na
 * SolicitacaoAumentoLimite, e sempre o GerenteRelacionamento que a originou -- o
 * {@code originadorId} da submissao.
 */
public record AtorHumano(AtorId id) implements AtorOperacao {

    public AtorHumano {
        Objects.requireNonNull(id, "id e obrigatorio");
    }
}
