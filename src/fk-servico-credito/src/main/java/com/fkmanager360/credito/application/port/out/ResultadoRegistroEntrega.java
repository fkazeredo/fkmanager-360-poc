package com.fkmanager360.credito.application.port.out;

/**
 * Desfecho de uma escrita de resultado (TX-B) em {@link EntregasEfetivacaoPort} (plano #0004,
 * secao 4 -- fencing). {@link #DESCARTADO_CLAIM_OBSOLETO} significa que o {@code claimId}
 * apresentado nao era mais o corrente: nenhum efeito foi aplicado -- nem em
 * {@code outbox_entrega}, nem em {@code solicitacao_aumento_limite}, nem historico, nem metrica de
 * resultado. {@link #APLICADO_COM_ANOMALIA_PROTOCOLO_DIVERGENTE} e o unico caso em que o efeito
 * foi aplicado (a entrega conclui) mas um sinal operacional adicional deve ser emitido: o
 * {@code ProtocoloCore} recebido agora diverge do ja persistido para o mesmo EfetivacaoId, e o
 * existente NUNCA e sobrescrito.
 */
public enum ResultadoRegistroEntrega {
    APLICADO,
    APLICADO_COM_ANOMALIA_PROTOCOLO_DIVERGENTE,
    DESCARTADO_CLAIM_OBSOLETO
}
