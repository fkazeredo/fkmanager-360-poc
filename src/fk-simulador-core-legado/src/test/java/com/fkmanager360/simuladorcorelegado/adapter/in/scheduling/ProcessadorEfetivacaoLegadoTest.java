package com.fkmanager360.simuladorcorelegado.adapter.in.scheduling;

import com.fkmanager360.simuladorcorelegado.adapter.out.callback.CallbackDispatcher;
import com.fkmanager360.simuladorcorelegado.adapter.out.callback.ConfirmacaoEfetivacao;
import com.fkmanager360.simuladorcorelegado.domain.ContasLegadoStore;
import com.fkmanager360.simuladorcorelegado.domain.EfetivacoesLegadoStore;
import com.fkmanager360.simuladorcorelegado.domain.EfetivacoesLegadoStore.PendenciaProcessamento;
import com.fkmanager360.simuladorcorelegado.domain.EfetivacoesLegadoStore.ResultadoConsultaStatus;
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
 * {@code ProcessadorEfetivacaoLegado} (#0005; #0006) com {@link ContasLegadoStore} e
 * {@link CallbackDispatcher} mockados, mas {@link EfetivacoesLegadoStore} REAL -- e um componente
 * de estado em memoria simples o bastante para nao valer a pena mockar, e os testes de #0006
 * precisam do seu comportamento real (modo de callback, pendencia). "processar" e "agendar" sao
 * provados separadamente: {@link #processar} direto, sem esperar o agendamento real;
 * {@code agendarProcessamento} capturando o {@link Runnable} que o {@link TaskScheduler} receberia
 * e executando-o manualmente, provando a composicao sem sleep algum.
 */
class ProcessadorEfetivacaoLegadoTest {

    private final ContasLegadoStore contasStore = mock(ContasLegadoStore.class);
    private final EfetivacoesLegadoStore efetivacoesStore = new EfetivacoesLegadoStore();
    private final CallbackDispatcher callbackDispatcher = mock(CallbackDispatcher.class);
    private final TaskScheduler taskScheduler = mock(TaskScheduler.class);

    private final ProcessadorEfetivacaoLegado processador = new ProcessadorEfetivacaoLegado(
            contasStore, efetivacoesStore, callbackDispatcher, taskScheduler, Duration.ofMillis(500));

    @Test
    void processar_mutaOLimiteEDisparaOCallbackDeSucesso() {
        String numPrt = efetivacoesStore.registrarAceite(
                "id-eft-1", "0000010001", "000000000500000", "000000000600000").registro().numPrt();

        processador.processar("id-eft-1", "0000010001", numPrt, "000000000600000", "id-cor-1");

        verify(contasStore).aplicarLimiteChequeEspecial("0000010001", "000000000600000");

        ArgumentCaptor<ConfirmacaoEfetivacao> confirmacao = ArgumentCaptor.forClass(ConfirmacaoEfetivacao.class);
        verify(callbackDispatcher).enviarConfirmacao(confirmacao.capture());
        assertThat(confirmacao.getValue().idEft()).isEqualTo("id-eft-1");
        assertThat(confirmacao.getValue().numPrt()).isEqualTo(numPrt);
        assertThat(confirmacao.getValue().vlrLimEft()).isEqualTo("000000000600000");
        assertThat(confirmacao.getValue().idCor()).isEqualTo("id-cor-1");

        assertThat(efetivacoesStore.consultarPorIdEft("id-eft-1"))
                .isEqualTo(new ResultadoConsultaStatus.Processada("id-eft-1", numPrt, "000000000600000"));
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

    // --- #0006: control plane de callback -------------------------------------------------------

    @Test
    void processar_comSuprimirCallbackArmado_mutaOLimiteMasNaoDisparaCallback_eEConsumidoUmaUnicaVez() {
        String numPrt1 = efetivacoesStore.registrarAceite(
                "id-eft-supr-1", "0000010001", "000000000500000", "000000000600000").registro().numPrt();
        efetivacoesStore.configurarSuprimirCallback("0000010001");

        processador.processar("id-eft-supr-1", "0000010001", numPrt1, "000000000600000", "id-cor-1");

        verify(contasStore).aplicarLimiteChequeEspecial("0000010001", "000000000600000");
        verify(callbackDispatcher, never()).enviarConfirmacao(any());
        assertThat(efetivacoesStore.consultarPorIdEft("id-eft-supr-1"))
                .isEqualTo(new ResultadoConsultaStatus.Processada("id-eft-supr-1", numPrt1, "000000000600000"));

        // Disparo unico: uma segunda efetivacao para a mesma conta ja processa normalmente.
        String numPrt2 = efetivacoesStore.registrarAceite(
                "id-eft-supr-2", "0000010001", "000000000600000", "000000000700000").registro().numPrt();
        processador.processar("id-eft-supr-2", "0000010001", numPrt2, "000000000700000", "id-cor-2");
        verify(callbackDispatcher).enviarConfirmacao(any());
    }

    @Test
    void processar_comSuspenderProcessamentoArmado_naoMutaNemDisparaAgora_eRegistraPendencia() {
        String numPrt = efetivacoesStore.registrarAceite(
                "id-eft-susp-1", "0000010001", "000000000500000", "000000000600000").registro().numPrt();
        efetivacoesStore.configurarSuspenderProcessamento("0000010001");

        processador.processar("id-eft-susp-1", "0000010001", numPrt, "000000000600000", "id-cor-1");

        verify(contasStore, never()).aplicarLimiteChequeEspecial(any(), any());
        verify(callbackDispatcher, never()).enviarConfirmacao(any());
        assertThat(efetivacoesStore.consultarPorIdEft("id-eft-susp-1"))
                .isEqualTo(new ResultadoConsultaStatus.EmProcessamento("id-eft-susp-1", numPrt));

        PendenciaProcessamento pendencia = efetivacoesStore.liberarPendencia("0000010001").orElseThrow();
        processador.processarPendenciaLiberada(pendencia);

        // #0006, achado do /code-review: liberar tambem passa pelo TaskScheduler -- nunca inline na
        // thread chamadora, porque termina em uma chamada HTTP sincrona de saida (callback).
        ArgumentCaptor<Runnable> tarefaLiberada = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(tarefaLiberada.capture(), any(Instant.class));
        tarefaLiberada.getValue().run();

        verify(contasStore).aplicarLimiteChequeEspecial("0000010001", "000000000600000");
        verify(callbackDispatcher).enviarConfirmacao(any());
        assertThat(efetivacoesStore.consultarPorIdEft("id-eft-susp-1"))
                .isEqualTo(new ResultadoConsultaStatus.Processada("id-eft-susp-1", numPrt, "000000000600000"));
    }
}
