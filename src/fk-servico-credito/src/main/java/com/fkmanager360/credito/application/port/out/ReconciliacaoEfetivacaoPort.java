package com.fkmanager360.credito.application.port.out;

import java.time.Duration;
import java.time.Instant;

/**
 * Porta de saida do bookkeeping de {@code reconciliacao_efetivacao} (#0006): claim/fencing/agenda,
 * espelhando exatamente o idioma de {@link EntregasEfetivacaoPort} do dispatcher (#0004) -- mesmo
 * {@code SKIP LOCKED} unitario, mesmo fencing por {@code claimId}, mesma separacao entre TX-A
 * (claim) e TX-B (aplicar resultado). Nunca conhece {@code outbox_entrega}: o reconciliador
 * pergunta, nunca entrega, e a ordem global de locks da plataforma
 * ({@code reconciliacao_efetivacao} -&gt; {@code solicitacao_aumento_limite}) preserva a ausencia
 * de deadlock com o callback puro e com o dispatcher exatamente pela mesma razao que ja vale entre
 * {@code outbox_entrega} e {@code solicitacao_aumento_limite}.
 *
 * <p><b>Conclusao nao mora aqui:</b> quem decide se um resultado do Core conclui a solicitacao e
 * {@code RegistrarResultadoEfetivacao}, dentro de uma {@link TransacaoPort} unica orquestrada por
 * {@code ReconciliarEfetivacoes}. Esta porta so contribui claim, fencing, terminalizacao da PROPRIA
 * agenda de reconciliacao e os dois reagendamentos (curto e pos-indeterminacao).
 */
public interface ReconciliacaoEfetivacaoPort {

    /**
     * TX-A: reclama atomicamente, sob {@code FOR UPDATE SKIP LOCKED}, o proximo ciclo
     * {@code PENDENTE} devido (claim livre ou expirado). Se a solicitacao correlacionada ja esta
     * terminal, terminaliza a linha dentro do MESMO lock, sem devolver claim -- nenhuma consulta ao
     * Core acontece para esse ciclo.
     */
    ReclamacaoReconciliacao reclamarProxima(Instant agora, Duration lease);

    /**
     * Fencing sob lock fresco: true somente se o ciclo ainda esta {@code PENDENTE} e o
     * {@code claimId} apresentado ainda e o corrente. <b>So pode ser chamado dentro de uma
     * {@link TransacaoPort} ativa</b> (propagacao {@code MANDATORY} no adapter).
     */
    boolean claimAindaValido(EfetivacaoReconciliacaoReclamada claim);

    /**
     * Fecha o ciclo de reconciliacao como {@code CONCLUIDA}: usado quando
     * {@code RegistrarResultadoEfetivacao} efetivamente terminalizou a solicitacao (por este ciclo
     * ou por outro caminho concorrente), ou quando o claim de TX-A ja a encontrou terminal. Mesma
     * exigencia de transacao ativa de {@link #claimAindaValido}.
     */
    void terminalizar(EfetivacaoReconciliacaoReclamada claim, Instant agora);

    /**
     * Libera o claim e reagenda para {@code proximaConsultaEm} com o backoff CURTO (ainda dentro da
     * janela normal, sem resultado autoritativo desta vez). Mesma exigencia de transacao ativa.
     */
    void reagendar(EfetivacaoReconciliacaoReclamada claim, Instant proximaConsultaEm, Instant agora);

    /**
     * Libera o claim e reagenda para {@code proximaConsultaEm} com o backoff LONGO da fase
     * pos-indeterminacao (polling de recuperacao de baixa frequencia, deliberado). Grava
     * {@code indeterminada_em} somente se ainda nao estiver preenchido -- idempotente entre a
     * primeira entrada e reentradas subsequentes sobre a mesma janela ja indeterminada. Mesma
     * exigencia de transacao ativa.
     */
    void reagendarAposIndeterminacao(EfetivacaoReconciliacaoReclamada claim, Instant proximaConsultaEm, Instant agora);
}
