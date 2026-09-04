package com.fkmanager360.credito.application.port.out;

/**
 * Os fatos que o historico funcional registra (spec, secao "Historico funcional"). #0003
 * introduziu os dois primeiros; #0004 acrescenta os tres fatos de entrega -- pela mesma regra que
 * rege {@code StatusSolicitacaoAumentoLimite} (ADR-0010): o vocabulario tecnico so nasce quando o
 * comportamento que o produz existe. Callback ({@code #0005}) e reconciliacao/indeterminacao
 * ({@code #0006}) permanecem fora deste enum ate seus proprios tickets.
 */
public enum TipoFatoHistorico {
    SOLICITACAO_REGISTRADA,
    DECISAO_AUTOMATICA_REGISTRADA,
    EFETIVACAO_SOLICITADA,
    INSTRUCAO_ACEITA_PELO_CORE,
    RESULTADO_EFETIVACAO_REGISTRADO
}
