package com.fkmanager360.simuladorcorelegado.adapter.in.scheduling;

import com.fkmanager360.simuladorcorelegado.adapter.out.callback.CallbackDispatcher;
import com.fkmanager360.simuladorcorelegado.adapter.out.callback.ConfirmacaoEfetivacao;
import com.fkmanager360.simuladorcorelegado.domain.ContasLegadoStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@code ProcessadorEfetivacaoLegado} (#0005) com colaboradores mockados -- "processar" e
 * "agendar" sao provados separadamente: {@link #processar} direto, sem esperar o agendamento
 * real; {@code agendarProcessamento} capturando o {@link Runnable} que o {@link TaskScheduler}
 * receberia e executando-o manualmente, provando a composicao sem sleep algum.
 */
class ProcessadorEfetivacaoLegadoTest {

    private final ContasLegadoStore contasStore = mock(ContasLegadoStore.class);
    private final CallbackDispatcher callbackDispatcher = mock(CallbackDispatcher.class);
    private final TaskScheduler taskScheduler = mock(TaskScheduler.class);

    private final ProcessadorEfetivacaoLegado processador =
            new ProcessadorEfetivacaoLegado(contasStore, callbackDispatcher, taskScheduler, Duration.ofMillis(500));

    @Test
    void processar_mutaOLimiteEDisparaOCallbackDeSucesso() {
        processador.processar("id-eft-1", "0000010001", "PRT-1", "000000000600000", "id-cor-1");

        verify(contasStore).aplicarLimiteChequeEspecial("0000010001", "000000000600000");

        ArgumentCaptor<ConfirmacaoEfetivacao> confirmacao = ArgumentCaptor.forClass(ConfirmacaoEfetivacao.class);
        verify(callbackDispatcher).enviarConfirmacao(confirmacao.capture());
        assertThat(confirmacao.getValue().idEft()).isEqualTo("id-eft-1");
        assertThat(confirmacao.getValue().numPrt()).isEqualTo("PRT-1");
        assertThat(confirmacao.getValue().vlrLimEft()).isEqualTo("000000000600000");
        assertThat(confirmacao.getValue().idCor()).isEqualTo("id-cor-1");
    }

    @Test
    void processar_falhaInesperadaNaMutacao_naoEscapaDoMetodoENaoDisparaCallback() {
        doThrow(new RuntimeException("boom")).when(contasStore).aplicarLimiteChequeEspecial(any(), any());

        processador.processar("id-eft-2", "0000010001", "PRT-2", "000000000600000", "id-cor-2");

        verify(callbackDispatcher, never()).enviarConfirmacao(any());
    }

    @Test
    void agendarProcessamento_delegaAoTaskSchedulerComOAtrasoConfigurado_eATarefaProcessaDeVerdade() {
        Instant antes = Instant.now();

        processador.agendarProcessamento("id-eft-3", "0000010001", "PRT-3", "000000000600000", "id-cor-3");

        ArgumentCaptor<Runnable> tarefa = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> instante = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler).schedule(tarefa.capture(), instante.capture());
        assertThat(instante.getValue()).isAfterOrEqualTo(antes.plusMillis(490));

        tarefa.getValue().run();

        verify(contasStore).aplicarLimiteChequeEspecial("0000010001", "000000000600000");
        verify(callbackDispatcher).enviarConfirmacao(any());
    }
}
