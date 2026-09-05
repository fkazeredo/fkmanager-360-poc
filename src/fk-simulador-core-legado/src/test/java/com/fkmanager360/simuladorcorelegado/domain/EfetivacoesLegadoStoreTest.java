package com.fkmanager360.simuladorcorelegado.domain;

import com.fkmanager360.simuladorcorelegado.domain.EfetivacoesLegadoStore.ModoCallback;
import com.fkmanager360.simuladorcorelegado.domain.EfetivacoesLegadoStore.PendenciaProcessamento;
import com.fkmanager360.simuladorcorelegado.domain.EfetivacoesLegadoStore.ResultadoConsultaStatus;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // --- Consulta de status (#0006) --------------------------------------------------------------

    @Test
    void consultarPorIdEft_semAceite_devolveDesconhecida() {
        EfetivacoesLegadoStore store = new EfetivacoesLegadoStore();

        assertThat(store.consultarPorIdEft("nunca-aceito")).isInstanceOf(ResultadoConsultaStatus.Desconhecida.class);
    }

    @Test
    void consultarPorIdEft_aceitoMasNaoProcessado_devolveEmProcessamento() {
        EfetivacoesLegadoStore store = new EfetivacoesLegadoStore();
        var registro = store.registrarAceite("id-eft-status-1", "0000010001", "000000000500000", "000000000600000");

        ResultadoConsultaStatus resultado = store.consultarPorIdEft("id-eft-status-1");

        assertThat(resultado).isEqualTo(new ResultadoConsultaStatus.EmProcessamento("id-eft-status-1", registro.registro().numPrt()));
    }

    @Test
    void marcarProcessada_consultaPorIdEftPassaADevolverProcessada() {
        EfetivacoesLegadoStore store = new EfetivacoesLegadoStore();
        var registro = store.registrarAceite("id-eft-status-2", "0000010001", "000000000500000", "000000000600000");

        store.marcarProcessada("id-eft-status-2", "000000000600000");

        assertThat(store.consultarPorIdEft("id-eft-status-2")).isEqualTo(
                new ResultadoConsultaStatus.Processada("id-eft-status-2", registro.registro().numPrt(), "000000000600000"));
    }

    @Test
    void consultarPorNumPrt_resolveOMesmoDesfechoQuePorIdEft() {
        EfetivacoesLegadoStore store = new EfetivacoesLegadoStore();
        var registro = store.registrarAceite("id-eft-status-3", "0000010001", "000000000500000", "000000000600000");
        store.marcarProcessada("id-eft-status-3", "000000000600000");

        assertThat(store.consultarPorNumPrt(registro.registro().numPrt()))
                .isEqualTo(store.consultarPorIdEft("id-eft-status-3"));
    }

    @Test
    void consultarPorNumPrt_desconhecido_devolveDesconhecida() {
        EfetivacoesLegadoStore store = new EfetivacoesLegadoStore();

        assertThat(store.consultarPorNumPrt("999999999999")).isInstanceOf(ResultadoConsultaStatus.Desconhecida.class);
    }

    // --- Control plane de callback (#0006) ---------------------------------------------------

    @Test
    void consultarEConsumirModoCallback_semConfiguracao_devolveNormal() {
        EfetivacoesLegadoStore store = new EfetivacoesLegadoStore();

        assertThat(store.consultarEConsumirModoCallback("0000010001")).isEqualTo(ModoCallback.NORMAL);
    }

    @Test
    void consultarEConsumirModoCallback_suprimir_eDisparoUnico() {
        EfetivacoesLegadoStore store = new EfetivacoesLegadoStore();
        store.configurarSuprimirCallback("0000010001");

        assertThat(store.consultarEConsumirModoCallback("0000010001")).isEqualTo(ModoCallback.SUPRIMIR);
        assertThat(store.consultarEConsumirModoCallback("0000010001")).isEqualTo(ModoCallback.NORMAL);
    }

    @Test
    void consultarEConsumirModoCallback_suspender_permaneceArmadoAteLiberarPendencia() {
        EfetivacoesLegadoStore store = new EfetivacoesLegadoStore();
        store.configurarSuspenderProcessamento("0000010001");

        assertThat(store.consultarEConsumirModoCallback("0000010001")).isEqualTo(ModoCallback.SUSPENDER);
        assertThat(store.consultarEConsumirModoCallback("0000010001")).isEqualTo(ModoCallback.SUSPENDER);

        store.registrarPendencia("0000010001", new PendenciaProcessamento("id-eft", "0000010001", "PRT-1", "000000000600000", "id-cor"));
        assertThat(store.liberarPendencia("0000010001")).isPresent();

        // Liberar limpa o modo -- proxima consulta ja e NORMAL, senao o processamento retomado se suspenderia de novo.
        assertThat(store.consultarEConsumirModoCallback("0000010001")).isEqualTo(ModoCallback.NORMAL);
    }

    @Test
    void liberarPendencia_semPendenciaRegistrada_devolveVazio() {
        EfetivacoesLegadoStore store = new EfetivacoesLegadoStore();

        assertThat(store.liberarPendencia("0000010001")).isEmpty();
    }

    /**
     * Achado do /code-review (#0006): uma UNICA pendencia suspensa por conta por vez -- sobrescrever
     * silenciosamente perderia o {@code idEft} anterior para sempre (nem processado, nem mais
     * recuperavel por {@link EfetivacoesLegadoStore#liberarPendencia}).
     */
    @Test
    void registrarPendencia_pendenciaJaExistenteParaAMesmaConta_lancaEPreservaAAnterior() {
        EfetivacoesLegadoStore store = new EfetivacoesLegadoStore();
        PendenciaProcessamento primeira = new PendenciaProcessamento("id-eft-1", "0000010001", "PRT-1", "000000000600000", "id-cor-1");
        store.registrarPendencia("0000010001", primeira);

        PendenciaProcessamento segunda = new PendenciaProcessamento("id-eft-2", "0000010001", "PRT-2", "000000000700000", "id-cor-2");
        assertThatThrownBy(() -> store.registrarPendencia("0000010001", segunda))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0000010001")
                .hasMessageContaining("id-eft-1");

        assertThat(store.liberarPendencia("0000010001")).contains(primeira);
    }

    /**
     * Achado do /code-review (#0006): liberar uma conta SEM pendencia suspensa (nunca suspensa, ou
     * ja liberada) e um no-op puro -- NAO pode apagar um {@code SUPRIMIR} ainda nao consumido, que e
     * um mecanismo independente do de suspensao.
     */
    @Test
    void liberarPendencia_semPendenciaRegistrada_naoApagaSuprimirCallbackAindaArmado() {
        EfetivacoesLegadoStore store = new EfetivacoesLegadoStore();
        store.configurarSuprimirCallback("0000010001");

        assertThat(store.liberarPendencia("0000010001")).isEmpty();

        assertThat(store.consultarEConsumirModoCallback("0000010001")).isEqualTo(ModoCallback.SUPRIMIR);
    }

    /**
     * Achado do /code-review (#0006): fim de cenario de teste (control plane {@code DELETE}) precisa
     * descartar uma pendencia suspensa nunca liberada -- senao um {@code liberar} tardio, de um
     * cenario futuro que reusa a mesma conta, ressuscitaria dados obsoletos (idEft/valor errados).
     */
    @Test
    void limparPendencia_descartaPendenciaSuspensaNuncaLiberada() {
        EfetivacoesLegadoStore store = new EfetivacoesLegadoStore();
        store.registrarPendencia("0000010001", new PendenciaProcessamento("id-eft-obsoleto", "0000010001", "PRT-1", "000000000600000", "id-cor"));

        store.limparPendencia("0000010001");

        assertThat(store.liberarPendencia("0000010001")).isEmpty();
    }
}
