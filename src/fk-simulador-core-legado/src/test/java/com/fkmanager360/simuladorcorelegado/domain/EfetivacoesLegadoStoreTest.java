package com.fkmanager360.simuladorcorelegado.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code criadoAgora} de {@link EfetivacoesLegadoStore#registrarAceite} (#0005, guardrail do
 * Owner): sob concorrencia real para o MESMO {@code idEft}, exatamente uma chamada deve observar
 * {@code criadoAgora=true} -- so ela deve agendar/processar a efetivacao
 * ({@code EfetivacaoLegadoController}).
 */
class EfetivacoesLegadoStoreTest {

    @Test
    void registrarAceite_primeiraChamada_criadoAgoraVerdadeiro() {
        EfetivacoesLegadoStore store = new EfetivacoesLegadoStore();

        var resultado = store.registrarAceite("id-eft-1", "0000010001", "000000000500000", "000000000600000");

        assertThat(resultado.criadoAgora()).isTrue();
        assertThat(resultado.registro().numPrt()).isNotBlank();
    }

    @Test
    void registrarAceite_segundaChamadaParaOMesmoIdEft_criadoAgoraFalsoEMesmoRegistro() {
        EfetivacoesLegadoStore store = new EfetivacoesLegadoStore();
        var primeira = store.registrarAceite("id-eft-2", "0000010001", "000000000500000", "000000000600000");

        var segunda = store.registrarAceite("id-eft-2", "0000010001", "000000000500000", "000000000600000");

        assertThat(segunda.criadoAgora()).isFalse();
        assertThat(segunda.registro().numPrt()).isEqualTo(primeira.registro().numPrt());
    }

    @Test
    void registrarAceite_chamadasConcorrentesParaOMesmoIdEft_apenasUmaTemCriadoAgoraVerdadeiro() throws Exception {
        EfetivacoesLegadoStore store = new EfetivacoesLegadoStore();
        int totalThreads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        try {
            CyclicBarrier barreira = new CyclicBarrier(totalThreads);
            List<Callable<Boolean>> tarefas = List.of(
                    () -> { barreira.await(); return store.registrarAceite(
                            "id-eft-concorrente", "0000010001", "000000000500000", "000000000600000").criadoAgora(); });

            List<Future<Boolean>> futuros = java.util.stream.IntStream.range(0, totalThreads)
                    .mapToObj(i -> executor.submit(tarefas.get(0)))
                    .collect(Collectors.toList());

            AtomicInteger totalCriadoAgora = new AtomicInteger();
            for (Future<Boolean> futuro : futuros) {
                if (futuro.get(10, TimeUnit.SECONDS)) {
                    totalCriadoAgora.incrementAndGet();
                }
            }

            assertThat(totalCriadoAgora.get()).isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }
}
