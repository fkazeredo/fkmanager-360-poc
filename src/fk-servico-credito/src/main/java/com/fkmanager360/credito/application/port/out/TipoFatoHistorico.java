package com.fkmanager360.credito.application.port.out;

/**
 * Os fatos que o historico funcional registra (spec, secao "Historico funcional"). #0003
 * introduziu os dois primeiros; #0004 acrescenta os tres fatos de entrega -- pela mesma regra que
 * rege {@code StatusSolicitacaoAumentoLimite} (ADR-0010): o vocabulario tecnico so nasce quando o
 * comportamento que o produz existe. #0005 (callback) reusa {@link #RESULTADO_EFETIVACAO_REGISTRADO}
 * sem fato novo -- so muda o {@code AtorOperacao}. #0006 acrescenta
 * {@link #EFETIVACAO_INDETERMINADA_REGISTRADA}, o unico fato realmente novo deste ticket: a
 * reconciliacao tambem conclui pelo mesmo {@link #RESULTADO_EFETIVACAO_REGISTRADO}, so com autor
 * {@code RECONCILIACAO_EFETIVACAO}.
 */
public enum TipoFatoHistorico {
    SOLICITACAO_REGISTRADA,
    DECISAO_AUTOMATICA_REGISTRADA,
    EFETIVACAO_SOLICITADA,
    INSTRUCAO_ACEITA_PELO_CORE,
    RESULTADO_EFETIVACAO_REGISTRADO,
    EFETIVACAO_INDETERMINADA_REGISTRADA
}
