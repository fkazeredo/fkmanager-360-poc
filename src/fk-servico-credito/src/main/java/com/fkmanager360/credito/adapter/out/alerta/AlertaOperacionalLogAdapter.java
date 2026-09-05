package com.fkmanager360.credito.adapter.out.alerta;

import com.fkmanager360.credito.application.port.out.AlertaOperacionalPort;
import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.SolicitacaoId;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Implementacao do sinal de alerta operacional (#0006, AC35): WARN estruturado com os
 * identificadores necessarios a investigacao -- {@code efetivacaoId} e {@code solicitacaoId} sao
 * UUIDs tecnicos, nao dado sensivel do Cliente (ADR-0017) -- mais o contador dedicado
 * {@code efetivacao_alertas_operacionais_total}, tag {@code tipo} fixa e de baixa cardinalidade
 * (AC36). Este e o ponto de troca real para uma ferramenta de alerta de verdade (PagerDuty,
 * Alertmanager sobre o padrao do log ou sobre a metrica): o resto da plataforma so conhece a porta.
 *
 * <p>O texto do log e deliberado: afirma ignorancia sobre o resultado, nunca falha de efetivacao
 * (ADR-0009, emenda) -- {@code EFETIVACAO_INDETERMINADA} pode muito bem terminar em sucesso.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class AlertaOperacionalLogAdapter implements AlertaOperacionalPort {

    private final MeterRegistry meterRegistry;

    @Override
    public void efetivacaoIndeterminada(EfetivacaoId efetivacaoId, SolicitacaoId solicitacaoId, Instant ocorridoEm) {
        log.warn("ALERTA_OPERACIONAL tipo=EFETIVACAO_INDETERMINADA efetivacaoId={} solicitacaoId={} ocorridoEm={} "
                        + "-- janela normal de recuperacao automatica esgotada sem resultado autoritativo; "
                        + "isto NAO afirma que a efetivacao falhou",
                efetivacaoId.valor(), solicitacaoId.valor(), ocorridoEm);
        meterRegistry.counter("efetivacao_alertas_operacionais_total", "tipo", "EFETIVACAO_INDETERMINADA").increment();
    }
}
