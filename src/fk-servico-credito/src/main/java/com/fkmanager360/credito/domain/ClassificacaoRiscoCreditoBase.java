package com.fkmanager360.credito.domain;

/**
 * Classificacao de risco simples que o CoreLegado ja mantem para a operacao bancaria corrente,
 * consultada por Credito pela sua propria ACL (ADR-0004).
 *
 * <p>E insumo interno, e <b>nao</b> e apresentada ao GerenteRelacionamento: o que a decisao
 * comunica e o MotivoDecisaoCredito, nunca a gradacao. Nao se confunde com AvaliacaoRisco nem
 * com ResultadoAvaliacaoRisco, que sao processamento especializado do contexto Risco.
 */
public enum ClassificacaoRiscoCreditoBase {
    BAIXO,
    MEDIO,
    ALTO
}
