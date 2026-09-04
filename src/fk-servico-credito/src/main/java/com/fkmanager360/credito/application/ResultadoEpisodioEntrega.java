package com.fkmanager360.credito.application;

import com.fkmanager360.credito.domain.MotivoFalhaEfetivacao;

import java.time.Duration;
import java.util.Objects;

/**
 * Saida de um episodio de {@code EntregarInstrucoesEfetivacao} (plano #0004, secoes 1 e 9): o
 * suficiente para o adapter de agendamento decidir se continua o loop de lote e para
 * {@code MetricasEntregaEfetivacao} incrementar exatamente o contador certo -- nunca para um
 * resultado {@link DescartadaPorFencing} ou {@link SemPendente}, que nao representam uma
 * transicao de negocio ou de entrega real.
 */
public sealed interface ResultadoEpisodioEntrega {

    /** Nenhuma entrega elegivel neste tick -- o loop de lote deve parar. */
    record SemPendente() implements ResultadoEpisodioEntrega {
    }

    /** Tentativas esgotadas terminalizaram a entrega agora, sem novo episodio HTTP (AC28). */
    record EsgotadaAgora() implements ResultadoEpisodioEntrega {
    }

    record Aceite() implements ResultadoEpisodioEntrega {
    }

    /** Aceite aplicado, mas o ProtocoloCore recebido diverge do ja persistido -- anomalia observavel. */
    record AceiteComAnomaliaProtocoloDivergente() implements ResultadoEpisodioEntrega {
    }

    /** Falha transitoria: reagendada com backoff, mesmo EfetivacaoId. */
    record Reagendada() implements ResultadoEpisodioEntrega {
    }

    /**
     * Retorno definitivo do Core: a solicitacao converge em FALHA_EFETIVACAO (AC15).
     * {@code permanenciaEmAguardandoEfetivacao} alimenta o meter de AC36 -- sem identificador de
     * negocio associado.
     */
    record FalhaDefinitiva(MotivoFalhaEfetivacao motivo, Duration permanenciaEmAguardandoEfetivacao) implements ResultadoEpisodioEntrega {
        public FalhaDefinitiva {
            Objects.requireNonNull(motivo, "motivo e obrigatorio");
            Objects.requireNonNull(permanenciaEmAguardandoEfetivacao, "permanenciaEmAguardandoEfetivacao e obrigatoria");
        }
    }

    /** Resposta indeterminada: o dispatcher para sem concluir nada (OD-3). */
    record Indeterminada() implements ResultadoEpisodioEntrega {
    }

    /** O claimId usado nao era mais o corrente -- nenhum efeito foi aplicado (fencing). */
    record DescartadaPorFencing() implements ResultadoEpisodioEntrega {
    }
}
