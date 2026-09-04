package com.fkmanager360.simuladorcorelegado.adapter.in.scheduling;

import com.fkmanager360.simuladorcorelegado.adapter.out.callback.CallbackDispatcher;
import com.fkmanager360.simuladorcorelegado.adapter.out.callback.ConfirmacaoEfetivacao;
import com.fkmanager360.simuladorcorelegado.domain.ContasLegadoStore;
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
 * <p>"Processar" e mutar {@link ContasLegadoStore} com o novo limite e disparar UMA tentativa
 * assincrona de callback ({@link CallbackDispatcher#enviarConfirmacao}) -- nunca um segundo
 * subsistema de retry aqui.
 */
@Component
@Slf4j
public class ProcessadorEfetivacaoLegado {

    private final ContasLegadoStore contasStore;
    private final CallbackDispatcher callbackDispatcher;
    private final TaskScheduler taskScheduler;
    private final Duration atraso;

    public ProcessadorEfetivacaoLegado(
            ContasLegadoStore contasStore,
            CallbackDispatcher callbackDispatcher,
            TaskScheduler taskScheduler,
            @Value("${simulador.callback.atraso:PT0.5S}") Duration atraso) {
        this.contasStore = contasStore;
        this.callbackDispatcher = callbackDispatcher;
        this.taskScheduler = taskScheduler;
        this.atraso = atraso;
    }

    public void agendarProcessamento(String idEft, String numCta, String numPrt, String vlrLimNov, String idCor) {
        taskScheduler.schedule(() -> processar(idEft, numCta, numPrt, vlrLimNov, idCor), Instant.now().plus(atraso));
    }

    /** Pacote-visivel de proposito: testes chamam diretamente, sem esperar o agendamento real. */
    void processar(String idEft, String numCta, String numPrt, String vlrLimNov, String idCor) {
        try {
            contasStore.aplicarLimiteChequeEspecial(numCta, vlrLimNov);
            callbackDispatcher.enviarConfirmacao(new ConfirmacaoEfetivacao(idEft, numPrt, vlrLimNov, idCor));
        } catch (RuntimeException e) {
            log.error("Processamento assincrono da efetivacao idEft={} falhou inesperadamente", idEft, e);
        }
    }
}
