package com.fkmanager360.credito.adapter.in.web;

import com.fkmanager360.credito.application.ResultadoSubmissao;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * O unico meter novo deste ticket: {@code decisoes_credito_total{resultado, motivo,
 * versao_politica}} (plano #0003, secao 10; ADR-0017; spec, secao "Observabilidade"). Vive em
 * {@code adapter.in.web}, nunca em {@code domain}/{@code application} -- o dominio nao chama
 * {@link MeterRegistry} nem qualquer API de observabilidade (ADR-0017, ADR-0020), e
 * {@code ArchitectureTest.dominio_nao_depende_de_micrometer} falha se isso mudar.
 *
 * <p><b>Conta decisoes, nao respostas (IMPORTANT 6 do plano).</b> {@link ResultadoSubmissao#decidiuAgora()}
 * -- e nao {@code criacaoNova()} -- e o sinal correto: uma retomada de {@code SOLICITADA}
 * interrompida tambem calcula e persiste uma decisao nova agora (embora {@code criacaoNova} seja
 * {@code false}, porque nada foi criado NESTA chamada), e por isso incrementa a metrica; um replay
 * puro (solicitacao ja decidida antes) nao incrementa. Labels de baixa cardinalidade apenas:
 * {@code resultado} (2 valores), {@code motivo} (4 valores) e {@code versao_politica} (1 valor
 * nesta etapa) -- nunca {@code clienteId}, {@code contaId}, {@code solicitacaoId} ou
 * {@code correlationId} (AC36 parcial).
 */
@Component
@RequiredArgsConstructor
class MetricasDecisaoCredito {

    private final MeterRegistry meterRegistry;

    void registrarDecisao(ResultadoSubmissao resultado) {
        if (!resultado.decidiuAgora()) {
            return;
        }

        meterRegistry.counter(
                "decisoes_credito_total",
                "resultado", resultado.decisao().resultado().name(),
                "motivo", resultado.decisao().motivo().name(),
                "versao_politica", resultado.decisao().versaoPoliticaCredito().valor())
                .increment();
    }
}
