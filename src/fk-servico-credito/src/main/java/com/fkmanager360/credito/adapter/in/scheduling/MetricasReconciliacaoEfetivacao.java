package com.fkmanager360.credito.adapter.in.scheduling;

import com.fkmanager360.credito.application.ResultadoCicloReconciliacao;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Meters de reconciliacao de efetivacao (#0006; AC35/AC36): {@code efetivacao_reconciliacao_consultas_total}
 * por {@code classe} (mesmo idioma de {@code efetivacao_entregas_total} em
 * {@link MetricasEntregaEfetivacao}) e {@code efetivacao_indeterminadas_total} (contador simples,
 * sem tags -- entradas em {@code EFETIVACAO_INDETERMINADA}, metrica exigida pela spec, secao
 * "Observabilidade"). Vive em {@code adapter.in.scheduling}, nunca em {@code domain}/
 * {@code application} -- o alerta em si (AC35) e uma porta separada,
 * {@code AlertaOperacionalPort}, adaptada em {@code adapter.out.alerta}.
 *
 * <p><b>So incrementa a partir do RETORNO do caso de uso</b> -- nunca de uma tentativa:
 * {@link ResultadoCicloReconciliacao.SemPendente} e
 * {@link ResultadoCicloReconciliacao.DescartadoPorFencing} nao incrementam nada. Nenhum label
 * carrega {@code clienteId}, {@code contaId}, {@code solicitacaoId}, {@code protocoloCore} ou
 * {@code correlationId} (AC36).
 */
@Component
@RequiredArgsConstructor
class MetricasReconciliacaoEfetivacao {

    private final MeterRegistry meterRegistry;

    void registrar(ResultadoCicloReconciliacao resultado) {
        switch (resultado) {
            case ResultadoCicloReconciliacao.SemPendente ignored -> {
            }
            case ResultadoCicloReconciliacao.DescartadoPorFencing ignored -> {
            }
            case ResultadoCicloReconciliacao.JaTerminalAoReclamar ignored -> classe("JA_TERMINAL");
            case ResultadoCicloReconciliacao.ConcluidaPorResultadoAutoritativo ignored -> classe("CONCLUIDA");
            case ResultadoCicloReconciliacao.ConcluidaPorOutroCaminho concluidaPorOutroCaminho -> {
                classe("CONCLUIDA");
                meterRegistry.counter("efetivacao_anomalias_total", "tipo", "RECONCILIACAO_CONCLUSAO_CONCORRENTE").increment();
                if (concluidaPorOutroCaminho.contraditoria()) {
                    meterRegistry.counter("efetivacao_anomalias_total", "tipo", "RECONCILIACAO_CONCLUSAO_CONCORRENTE_CONTRADITORIA")
                            .increment();
                }
            }
            case ResultadoCicloReconciliacao.ReagendadaSemResultadoAutoritativo ignored -> classe("SEM_RESULTADO");
            case ResultadoCicloReconciliacao.ReagendadaPorResultadoIncoerente ignored -> {
                classe("SEM_RESULTADO");
                meterRegistry.counter("efetivacao_anomalias_total", "tipo", "RECONCILIACAO_RESULTADO_INCOERENTE").increment();
            }
            case ResultadoCicloReconciliacao.IndeterminadaAgora ignored -> {
                classe("INDETERMINADA");
                meterRegistry.counter("efetivacao_indeterminadas_total").increment();
            }
            case ResultadoCicloReconciliacao.JaEstavaIndeterminada jaEstavaIndeterminada -> {
                classe("INDETERMINADA");
                if (jaEstavaIndeterminada.incoerente()) {
                    meterRegistry.counter("efetivacao_anomalias_total", "tipo", "RECONCILIACAO_RESULTADO_INCOERENTE").increment();
                }
            }
        }
    }

    /** Ciclo que lancou excecao antes de produzir um {@link ResultadoCicloReconciliacao}. */
    void registrarErroInesperado() {
        meterRegistry.counter("efetivacao_reconciliacao_erros_inesperados_total").increment();
    }

    private void classe(String classe) {
        meterRegistry.counter("efetivacao_reconciliacao_consultas_total", "classe", classe).increment();
    }
}
