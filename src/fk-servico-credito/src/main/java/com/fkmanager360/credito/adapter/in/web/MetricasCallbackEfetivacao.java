package com.fkmanager360.credito.adapter.in.web;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Meters do callback de confirmacao (#0005; AC36): {@code efetivacao_callback_resultados_total}
 * por {@code resultado} (4 valores fixos, espelhando {@link CallbackEfetivacaoResponse}) e o
 * timer {@code efetivacao_tempo_aguardando_efetivacao} (mesmo meter que #0004 ja usa para a
 * conclusao pelo dispatcher -- e a MESMA medida de negocio, so por caminho diferente). Anomalias
 * reusam {@code efetivacao_anomalias_total{tipo}}, ja introduzido em #0004 para protocolo
 * divergente -- chave de tag unica, registries estritos (Prometheus) continuam validos. Nenhum
 * label carrega {@code clienteId}, {@code contaId}, {@code solicitacaoId}, {@code protocoloCore}
 * ou {@code correlationId}.
 */
@Component
@RequiredArgsConstructor
class MetricasCallbackEfetivacao {

    private final MeterRegistry meterRegistry;

    void registrarResultado(String resultado) {
        meterRegistry.counter("efetivacao_callback_resultados_total", "resultado", resultado).increment();
    }

    void registrarConclusaoAgora(Duration permanenciaEmAguardandoEfetivacao) {
        meterRegistry.timer("efetivacao_tempo_aguardando_efetivacao").record(permanenciaEmAguardandoEfetivacao);
    }

    void registrarAnomalia(String tipo) {
        meterRegistry.counter("efetivacao_anomalias_total", "tipo", tipo).increment();
    }
}
