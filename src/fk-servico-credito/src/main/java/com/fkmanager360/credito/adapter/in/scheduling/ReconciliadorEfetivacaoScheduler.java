package com.fkmanager360.credito.adapter.in.scheduling;

import com.fkmanager360.credito.application.ResultadoCicloReconciliacao;
import com.fkmanager360.credito.application.usecase.ReconciliarEfetivacoes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * O reconciliador de efetivacao, casca fina sobre {@link ReconciliarEfetivacoes} (spec, secao
 * "Reconciliacao"; #0006) -- espelha 1:1 o papel de {@code DispatcherEfetivacaoScheduler} (#0004):
 * {@code @Scheduled} do proprio Spring, sem eleicao de lider (OD-1 de #0004, reafirmado aqui). Loop
 * de claim UNITARIO: "ate {@code lote} ciclos por tick" e responsabilidade deste adapter, nunca da
 * porta de persistencia -- cada iteracao reclama, consulta e persiste UM ciclo (TX-A -&gt; commit -&gt;
 * HTTP -&gt; TX-B -&gt; commit) antes do proximo. Sem worker pool proprio: o paralelismo entre
 * multiplas instancias vem inteiramente do {@code SKIP LOCKED} no claim (AC34).
 *
 * <p>Desligavel por {@code credito.efetivacao.reconciliacao.habilitada=false} -- usado pelos testes
 * S6 que sobem o contexto Spring sem depender do agendamento real.
 *
 * <p>Um ciclo que lanca (ex.: {@code DataAccessException} sob contencao de {@code FOR UPDATE}) nao
 * aborta o tick inteiro: e capturado, contado como anomalia e o loop segue para o proximo ciclo do
 * lote -- mesma razao do dispatcher.
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "credito.efetivacao.reconciliacao", name = "habilitada", havingValue = "true", matchIfMissing = true)
public class ReconciliadorEfetivacaoScheduler {

    private final ReconciliarEfetivacoes reconciliarEfetivacoes;
    private final MetricasReconciliacaoEfetivacao metricas;
    private final int lote;

    public ReconciliadorEfetivacaoScheduler(
            ReconciliarEfetivacoes reconciliarEfetivacoes,
            MetricasReconciliacaoEfetivacao metricas,
            @Value("${credito.efetivacao.reconciliacao.lote:10}") int lote) {
        this.reconciliarEfetivacoes = reconciliarEfetivacoes;
        this.metricas = metricas;
        if (lote < 1) {
            throw new IllegalArgumentException("credito.efetivacao.reconciliacao.lote deve ser >= 1: " + lote);
        }
        this.lote = lote;
    }

    @Scheduled(fixedDelayString = "${credito.efetivacao.reconciliacao.poll-interval:PT30S}")
    void executarTick() {
        for (int ciclo = 0; ciclo < lote; ciclo++) {
            ResultadoCicloReconciliacao resultado;
            try {
                resultado = reconciliarEfetivacoes.executarUmCiclo();
            } catch (RuntimeException e) {
                log.error("Ciclo de reconciliacao falhou inesperadamente -- tick continua com o proximo", e);
                metricas.registrarErroInesperado();
                continue;
            }
            metricas.registrar(resultado);

            if (resultado instanceof ResultadoCicloReconciliacao.DescartadoPorFencing) {
                log.warn("Ciclo de reconciliacao descartado por fencing -- claim obsoleto, nenhum efeito aplicado");
            }
            if (resultado instanceof ResultadoCicloReconciliacao.SemPendente) {
                // Nada mais a fazer neste tick -- nao vale a pena continuar o loop.
                break;
            }
        }
    }
}
