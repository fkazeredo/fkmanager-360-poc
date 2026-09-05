package com.fkmanager360.credito.application;

import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;

import java.util.Objects;

/**
 * Saida de um ciclo de {@code ReconciliarEfetivacoes} (#0006): o suficiente para o adapter de
 * agendamento decidir se continua o loop de lote, e para {@code MetricasReconciliacaoEfetivacao}
 * incrementar exatamente o contador certo -- espelha o papel de {@code ResultadoEpisodioEntrega}
 * (#0004) no dispatcher.
 */
public sealed interface ResultadoCicloReconciliacao {

    /** Nenhum ciclo de reconciliacao elegivel neste tick -- o loop de lote deve parar. */
    record SemPendente() implements ResultadoCicloReconciliacao {
    }

    /** A solicitacao ja estava terminal no proprio claim (TX-A) -- nenhuma consulta ao Core ocorreu. */
    record JaTerminalAoReclamar(StatusSolicitacaoAumentoLimite statusPersistido) implements ResultadoCicloReconciliacao {
        public JaTerminalAoReclamar {
            Objects.requireNonNull(statusPersistido, "statusPersistido e obrigatorio");
        }
    }

    /** O claimId usado nao era mais o corrente ao abrir a TX-B -- nenhum efeito foi aplicado (fencing). */
    record DescartadoPorFencing() implements ResultadoCicloReconciliacao {
    }

    /** O Core respondeu de forma autoritativa e coerente, e este ciclo concluiu a solicitacao. */
    record ConcluidaPorResultadoAutoritativo(StatusSolicitacaoAumentoLimite statusResultante) implements ResultadoCicloReconciliacao {
        public ConcluidaPorResultadoAutoritativo {
            Objects.requireNonNull(statusResultante, "statusResultante e obrigatorio");
        }
    }

    /**
     * Outro caminho (tipicamente callback) ja terminalizou a solicitacao antes desta TX-B --
     * {@code contraditoria} distingue coincidencia de contradicao, so para fins de anomalia
     * observavel (mesma semantica de {@code ResultadoConclusaoDefinitiva.ConcluidaPorOutroCaminho}
     * do dispatcher).
     */
    record ConcluidaPorOutroCaminho(StatusSolicitacaoAumentoLimite statusPersistido, boolean contraditoria) implements ResultadoCicloReconciliacao {
        public ConcluidaPorOutroCaminho {
            Objects.requireNonNull(statusPersistido, "statusPersistido e obrigatorio");
        }
    }

    /**
     * O Core nao devolveu resultado autoritativo (ainda em processamento, desconhecida ou
     * indeterminada) e a janela normal ainda nao expirou -- reagendado com backoff curto.
     */
    record ReagendadaSemResultadoAutoritativo() implements ResultadoCicloReconciliacao {
    }

    /**
     * O Core respondeu sucesso/protocolo, mas incoerente com o que ja se sabe (AC26 equivalente
     * aqui: sucesso incoerente ou protocolo divergente) -- NUNCA transiciona nem terminaliza a
     * reconciliacao; reagendado com backoff curto quando a janela ainda nao expirou.
     */
    record ReagendadaPorResultadoIncoerente() implements ResultadoCicloReconciliacao {
    }

    /** A janela normal esgotou agora e a solicitacao acabou de entrar em EFETIVACAO_INDETERMINADA (AC16/AC35). */
    record IndeterminadaAgora() implements ResultadoCicloReconciliacao {
    }

    /**
     * A solicitacao ja estava EFETIVACAO_INDETERMINADA -- polling de baixa frequencia continua, sem
     * novo alerta. {@code incoerente} distingue uma resposta autoritativa incoerente do Core
     * (sucesso incoerente ou protocolo divergente) chegando NESTA fase -- anomalia observavel por
     * si so, independente do estado ja ser indeterminado (mesmo papel de
     * {@link ConcluidaPorOutroCaminho#contraditoria}).
     */
    record JaEstavaIndeterminada(boolean incoerente) implements ResultadoCicloReconciliacao {
    }
}
