package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.MotivoFalhaEfetivacao;

import java.util.Objects;

/**
 * O resultado autoritativo de uma efetivacao, recebido por qualquer um dos caminhos que convergem
 * em {@code RegistrarResultadoEfetivacao} (ADR-0009): a propria instrucao (#0004, quando o aceite
 * ja traz recusa definitiva), o callback (#0005) ou a reconciliacao (#0006). #0004 so produzia
 * {@link FalhaDefinitiva} -- {@link Sucesso}, com {@code limiteEfetivadoCentavos}, nasce em #0005
 * junto do callback, que e quem primeiro precisa dela.
 */
public sealed interface ResultadoEfetivacaoRecebido {

    record FalhaDefinitiva(MotivoFalhaEfetivacao motivo) implements ResultadoEfetivacaoRecebido {
        public FalhaDefinitiva {
            Objects.requireNonNull(motivo, "motivo e obrigatorio");
        }
    }

    /**
     * Sucesso autoritativo (#0005, AC1/AC26): {@code limiteEfetivadoCentavos} e o valor que o Core
     * diz ter aplicado -- {@code JpaResultadoEfetivacaoAdapter} confere que ele coincide com o
     * {@code LimiteSolicitado} congelado no {@code ContextoDecisaoCredito} antes de transicionar
     * para {@code EFETIVADA}; divergencia e "sucesso incoerente" (AC26), nunca aplicada.
     */
    record Sucesso(long limiteEfetivadoCentavos) implements ResultadoEfetivacaoRecebido {
        public Sucesso {
            if (limiteEfetivadoCentavos <= 0) {
                throw new IllegalArgumentException("limiteEfetivadoCentavos deve ser positivo: " + limiteEfetivadoCentavos);
            }
        }
    }
}
