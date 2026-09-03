package com.fkmanager360.credito.application.port.out;

/**
 * Os fatos que o historico funcional registra nesta etapa (spec, secao "Historico funcional").
 * Fatos de efetivacao ({@code EFETIVACAO_SOLICITADA}, {@code INSTRUCAO_ACEITA_PELO_CORE}, etc.)
 * pertencem a #0004+ e nao existem aqui, pela mesma regra que rege
 * {@code StatusSolicitacaoAumentoLimite} (ADR-0010): o vocabulario tecnico so nasce quando o
 * comportamento que o produz existe.
 */
public enum TipoFatoHistorico {
    SOLICITACAO_REGISTRADA,
    DECISAO_AUTOMATICA_REGISTRADA
}
