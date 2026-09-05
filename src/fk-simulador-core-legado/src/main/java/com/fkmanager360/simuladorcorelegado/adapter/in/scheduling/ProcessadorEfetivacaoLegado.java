package com.fkmanager360.simuladorcorelegado.adapter.in.scheduling;

import com.fkmanager360.simuladorcorelegado.adapter.out.callback.CallbackDispatcher;
import com.fkmanager360.simuladorcorelegado.adapter.out.callback.ConfirmacaoEfetivacao;
import com.fkmanager360.simuladorcorelegado.domain.ContasLegadoStore;
import com.fkmanager360.simuladorcorelegado.domain.EfetivacoesLegadoStore;
import com.fkmanager360.simuladorcorelegado.domain.EfetivacoesLegadoStore.ModoCallback;
import com.fkmanager360.simuladorcorelegado.domain.EfetivacoesLegadoStore.PendenciaProcessamento;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Processamento assincrono de uma efetivacao aceita (spec, secao "Callback"; ticket #0005):
 * agendado com um atraso curto apos o aceite -- inclusive quando a RESPOSTA do aceite se perde
 * (cenario {@code PerderAceite} do control plane), porque a resposta perdida nao significa que o
 * processamento nao aconteceu; e exatamente esse descompasso que produz o callback antecipado
 * real com relacao a TX-B do dispatcher (AC14).
 *
 * <p>"Processar" e mutar {@link ContasLegadoStore} com o novo limite, marcar o desfecho em
 * {@link EfetivacoesLegadoStore} (#0006 -- e o que a consulta de status passa a refletir) e
 * disparar UMA tentativa assincrona de callback ({@link CallbackDispatcher#enviarConfirmacao}) --
 * nunca um segundo subsistema de retry aqui.
 *
 * <p><b>#0006, control plane de callback:</b> {@link ModoCallback#SUPRIMIR} processa normalmente
 * (limite muda, desfecho registrado) mas nunca dispara o callback -- habilita a jornada 3.
 * {@link ModoCallback#SUSPENDER} nao processa nada agora: guarda os dados em
 * {@link EfetivacoesLegadoStore#registrarPendencia} e so processa quando o control plane liberar
 * explicitamente ({@code POST .../liberar}) -- habilita a jornada 4 (janela de reconciliacao esgota
 * enquanto suspenso, entra em indeterminada; liberar mais tarde ainda conclui).
 */
@Component
@Slf4j
public class ProcessadorEfetivacaoLegado {

    private final ContasLegadoStore contasStore;
    private final EfetivacoesLegadoStore efetivacoesStore;
    private final CallbackDispatcher callbackDispatcher;
    private final TaskScheduler taskScheduler;
    private final Duration atraso;

    public ProcessadorEfetivacaoLegado(
            ContasLegadoStore contasStore,
            EfetivacoesLegadoStore efetivacoesStore,
            CallbackDispatcher callbackDispatcher,
            TaskScheduler taskScheduler,
            @Value("${simulador.callback.atraso:PT0.5S}") Duration atraso) {
        this.contasStore = contasStore;
        this.efetivacoesStore = efetivacoesStore;
        this.callbackDispatcher = callbackDispatcher;
        this.taskScheduler = taskScheduler;
        this.atraso = atraso;
    }

    public void agendarProcessamento(String idEft, String numCta, String numPrt, String vlrLimNov, String idCor) {
        taskScheduler.schedule(() -> processar(idEft, numCta, numPrt, vlrLimNov, idCor), Instant.now().plus(atraso));
    }

    /**
     * Retoma um processamento suspenso liberado pelo control plane (#0006): mesma logica de
     * {@link #processar}, agendada no MESMO {@link TaskScheduler} de thread unica que o fluxo
     * normal usa -- nunca inline na thread HTTP que atende {@code POST .../liberar}, porque
     * {@code processar} termina chamando {@link CallbackDispatcher#enviarConfirmacao}, uma
     * chamada HTTP sincrona de saida; rodar isso na thread de requisicao quebraria o invariante
     * "nunca sincronamente" que o resto desta classe preserva. Publico porque o control plane vive
     * em {@code adapter.in.web}, pacote distinto deste.
     */
    public void processarPendenciaLiberada(PendenciaProcessamento pendencia) {
        taskScheduler.schedule(
                () -> processar(pendencia.idEft(), pendencia.numCta(), pendencia.numPrt(), pendencia.vlrLimNov(), pendencia.idCor()),
                Instant.now());
    }

    /** Pacote-visivel de proposito: testes chamam diretamente, sem esperar o agendamento real. */
    void processar(String idEft, String numCta, String numPrt, String vlrLimNov, String idCor) {
        try {
            ModoCallback modo = efetivacoesStore.consultarEConsumirModoCallback(numCta);
            if (modo == ModoCallback.SUSPENDER) {
                efetivacoesStore.registrarPendencia(numCta, new PendenciaProcessamento(idEft, numCta, numPrt, vlrLimNov, idCor));
                return;
            }

            contasStore.aplicarLimiteChequeEspecial(numCta, vlrLimNov);
            efetivacoesStore.marcarProcessada(idEft, vlrLimNov);
            if (modo != ModoCallback.SUPRIMIR) {
                callbackDispatcher.enviarConfirmacao(new ConfirmacaoEfetivacao(idEft, numPrt, vlrLimNov, idCor));
            }
        } catch (RuntimeException e) {
            log.error("Processamento assincrono da efetivacao idEft={} falhou inesperadamente", idEft, e);
        }
    }
}
