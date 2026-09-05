package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;

import java.util.Objects;

/**
 * Resultado de {@link ResultadoEfetivacaoPort#registrarIndeterminacao} (#0006, AC16/AC35): a
 * transicao PERSISTIDA determina se uma indeterminacao nova nasceu agora -- observabilidade
 * (metrica + alerta) segue exclusivamente {@link IndeterminadaAgora}, nunca uma tentativa. Estado
 * funcional primeiro, observabilidade depois: nenhuma tentativa de tornar PostgreSQL, log e
 * Micrometer atomicamente exactly-once.
 */
public sealed interface ResultadoIndeterminacao {

    /**
     * A chamada aplicou a transicao {@code AGUARDANDO_EFETIVACAO -> EFETIVACAO_INDETERMINADA}
     * agora: unico caso que dispara o alerta operacional inicial (AC35) e o incremento do meter de
     * entrada em indeterminada.
     */
    record IndeterminadaAgora() implements ResultadoIndeterminacao {
    }

    /**
     * Ja estava {@code EFETIVACAO_INDETERMINADA} (reconciliacao reentrante sobre a mesma janela
     * esgotada, ou nova consulta apos indeterminada usando o backoff-longo). No-op idempotente --
     * nenhum novo alerta, nenhuma nova entrada de historico.
     */
    record JaEstavaIndeterminada() implements ResultadoIndeterminacao {
    }

    /**
     * Ja terminal ({@code EFETIVADA} ou {@code FALHA_EFETIVACAO}) -- um resultado autoritativo
     * concluiu a operacao antes desta chamada. Nao ha indeterminacao a registrar; a
     * reconciliacao terminaliza sua propria agenda a partir deste sinal.
     */
    record JaTerminal(StatusSolicitacaoAumentoLimite statusPersistido) implements ResultadoIndeterminacao {
        public JaTerminal {
            Objects.requireNonNull(statusPersistido, "statusPersistido e obrigatorio");
        }
    }
}
