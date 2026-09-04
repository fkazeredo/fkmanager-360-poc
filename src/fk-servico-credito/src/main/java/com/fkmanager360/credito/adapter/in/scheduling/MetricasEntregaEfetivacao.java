package com.fkmanager360.credito.adapter.in.scheduling;

import com.fkmanager360.credito.application.ResultadoEpisodioEntrega;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Meters de entrega de efetivacao (plano #0004, secao 9; AC36): {@code efetivacao_entregas_total}
 * por {@code classe} (5 valores: ACEITE, TRANSITORIO, DEFINITIVO, INDETERMINADO, ESGOTADA) e
 * {@code efetivacao_tempo_aguardando_efetivacao} (timer, sem label de negocio). Vive em
 * {@code adapter.in.scheduling}, nunca em {@code domain}/{@code application} -- o dominio nao
 * chama {@link MeterRegistry} (ADR-0017, ADR-0020).
 *
 * <p><b>So incrementa a partir do RETORNO do caso de uso</b> -- nunca a partir de uma tentativa:
 * {@link ResultadoEpisodioEntrega.SemPendente} e {@link ResultadoEpisodioEntrega.DescartadaPorFencing}
 * nao incrementam nada, porque nenhum deles representa uma transicao de entrega ou de negocio real
 * (decisao do Owner sobre fencing). Nenhum label carrega {@code clienteId}, {@code contaId},
 * {@code solicitacaoId}, {@code protocoloCore} ou {@code correlationId}.
 *
 * <p><b>Todo meter tem um unico conjunto fixo de chaves de tag.</b> Registries estritos (ex.:
 * Prometheus) rejeitam duas series do mesmo nome com chaves de tag diferentes -- por isso
 * {@code efetivacao_entregas_total} SEMPRE carrega so {@code classe}, nunca tambem {@code motivo};
 * o motivo da falha definitiva vai para um contador proprio, de cardinalidade igualmente limitada
 * (4 valores fixos de {@code MotivoFalhaEfetivacao}).
 */
@Component
@RequiredArgsConstructor
class MetricasEntregaEfetivacao {

    private final MeterRegistry meterRegistry;

    void registrar(ResultadoEpisodioEntrega resultado) {
        switch (resultado) {
            case ResultadoEpisodioEntrega.SemPendente ignored -> {
            }
            case ResultadoEpisodioEntrega.DescartadaPorFencing ignored -> {
            }
            case ResultadoEpisodioEntrega.EsgotadaAgora ignored -> classe("ESGOTADA");
            case ResultadoEpisodioEntrega.Aceite ignored -> classe("ACEITE");
            case ResultadoEpisodioEntrega.AceiteComAnomaliaProtocoloDivergente ignored -> {
                classe("ACEITE");
                meterRegistry.counter("efetivacao_anomalias_total", "tipo", "PROTOCOLO_DIVERGENTE").increment();
            }
            case ResultadoEpisodioEntrega.Reagendada ignored -> classe("TRANSITORIO");
            case ResultadoEpisodioEntrega.FalhaDefinitiva falhaDefinitiva -> {
                classe("DEFINITIVO");
                meterRegistry.counter("efetivacao_falhas_definitivas_total", "motivo", falhaDefinitiva.motivo().name())
                        .increment();
                meterRegistry.timer("efetivacao_tempo_aguardando_efetivacao")
                        .record(falhaDefinitiva.permanenciaEmAguardandoEfetivacao());
            }
            case ResultadoEpisodioEntrega.Indeterminada ignored -> classe("INDETERMINADO");
        }
    }

    /** Episodio que lancou excecao antes de produzir um {@link ResultadoEpisodioEntrega}. */
    void registrarErroInesperado() {
        meterRegistry.counter("efetivacao_erros_inesperados_total").increment();
    }

    private void classe(String classe) {
        meterRegistry.counter("efetivacao_entregas_total", "classe", classe).increment();
    }
}
