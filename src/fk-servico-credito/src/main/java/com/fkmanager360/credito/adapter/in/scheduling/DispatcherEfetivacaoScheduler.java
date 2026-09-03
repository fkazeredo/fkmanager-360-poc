package com.fkmanager360.credito.adapter.in.scheduling;

import com.fkmanager360.credito.application.ResultadoEpisodioEntrega;
import com.fkmanager360.credito.application.usecase.EntregarInstrucoesEfetivacao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * O dispatcher de efetivacao, casca fina sobre {@link EntregarInstrucoesEfetivacao} (spec, secao
 * "Dispatcher"; plano #0004, secao 1 -- OD-1: {@code @Scheduled} do proprio Spring, sem eleicao de
 * lider). Loop de claim UNITARIO: "ate {@code lote} episodios por tick" e responsabilidade deste
 * adapter, nunca da porta de persistencia -- cada iteracao reclama, entrega e persiste UM episodio
 * (TX-A -&gt; commit -&gt; HTTP -&gt; TX-B -&gt; commit) antes da proxima. Sem worker pool proprio: o
 * paralelismo entre multiplas instancias vem inteiramente do {@code SKIP LOCKED} no claim.
 *
 * <p>Desligavel por {@code credito.efetivacao.entrega.habilitada=false} -- usado pelos testes S6
 * que sobem o contexto Spring sem depender do agendamento real.
 *
 * <p>Um episodio que lanca (ex.: {@code DataAccessException} sob contencao de {@code FOR UPDATE})
 * nao aborta o tick inteiro: e capturado, contado como anomalia e o loop segue para o proximo
 * episodio do lote -- sem isto, uma unica linha problematica (ou um deadlock transiente do
 * Postgres) esfomearia todo o resto do lote a cada tick, silenciosamente, ate o proximo ciclo.
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "credito.efetivacao.entrega", name = "habilitada", havingValue = "true", matchIfMissing = true)
public class DispatcherEfetivacaoScheduler {

    private final EntregarInstrucoesEfetivacao entregarInstrucoesEfetivacao;
    private final MetricasEntregaEfetivacao metricas;
    private final int lote;

    public DispatcherEfetivacaoScheduler(
            EntregarInstrucoesEfetivacao entregarInstrucoesEfetivacao,
            MetricasEntregaEfetivacao metricas,
            @Value("${credito.efetivacao.entrega.lote:10}") int lote) {
        this.entregarInstrucoesEfetivacao = entregarInstrucoesEfetivacao;
        this.metricas = metricas;
        if (lote < 1) {
            throw new IllegalArgumentException("credito.efetivacao.entrega.lote deve ser >= 1: " + lote);
        }
        this.lote = lote;
    }

    @Scheduled(fixedDelayString = "${credito.efetivacao.entrega.poll-interval:PT1S}")
    void executarTick() {
        for (int episodio = 0; episodio < lote; episodio++) {
            ResultadoEpisodioEntrega resultado;
            try {
                resultado = entregarInstrucoesEfetivacao.executarUmEpisodio();
            } catch (RuntimeException e) {
                log.error("Episodio de entrega falhou inesperadamente -- tick continua com o proximo", e);
                metricas.registrarErroInesperado();
                continue;
            }
            metricas.registrar(resultado);

            if (resultado instanceof ResultadoEpisodioEntrega.DescartadaPorFencing) {
                log.warn("Episodio de entrega descartado por fencing -- claim obsoleto, nenhum efeito aplicado");
            }
            if (resultado instanceof ResultadoEpisodioEntrega.SemPendente) {
                // Nada mais a fazer neste tick -- nao vale a pena continuar o loop.
                break;
            }
        }
    }
}
