package com.fkmanager360.credito.domain;

/**
 * Decisao com consequencia formal sobre a solicitacao -- APROVADA ou REJEITADA (CONTEXT.md de
 * Credito). Enum separado de {@link StatusSolicitacaoAumentoLimite} deliberadamente: aprovacao e
 * resultado de decisao, nunca estado de workflow (glossario, entrada de
 * StatusSolicitacaoAumentoLimite, "_Evitar_").
 */
public enum ResultadoDecisaoCredito {
    APROVADA,
    REJEITADA
}
