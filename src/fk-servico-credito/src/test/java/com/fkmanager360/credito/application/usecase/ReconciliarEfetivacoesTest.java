package com.fkmanager360.credito.application.usecase;

import com.fkmanager360.credito.application.PoliticaRetryEntrega;
import com.fkmanager360.credito.application.ResultadoCicloReconciliacao;
import com.fkmanager360.credito.application.port.out.AlertaOperacionalPort;
import com.fkmanager360.credito.application.port.out.ConsultaStatusEfetivacaoCorePort;
import com.fkmanager360.credito.application.port.out.EfetivacaoReconciliacaoReclamada;
import com.fkmanager360.credito.application.port.out.EntregaEfetivacaoReclamada;
import com.fkmanager360.credito.application.port.out.EntregasEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ReclamacaoEntrega;
import com.fkmanager360.credito.application.port.out.ReclamacaoReconciliacao;
import com.fkmanager360.credito.application.port.out.ReconciliacaoEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ResultadoConsultaStatusCore;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoRecebido;
import com.fkmanager360.credito.application.port.out.ResultadoIndeterminacao;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroEfetivacao;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroEntrega;
import com.fkmanager360.credito.application.port.out.TransacaoPort;
import com.fkmanager360.credito.domain.AtorOperacao;
import com.fkmanager360.credito.domain.AtorSistema;
import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.MotivoFalhaEfetivacao;
import com.fkmanager360.credito.domain.ProtocoloCore;
import com.fkmanager360.credito.domain.SolicitacaoAumentoLimite;
import com.fkmanager360.credito.domain.SolicitacaoId;
import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2 (ADR-0018): {@code ReconciliarEfetivacoes} (#0006) com fakes comportamentais -- fronteira
 * estrita com o dispatcher (o reconciliador so pergunta, nunca reenvia -- estruturalmente garantido
 * aqui porque este caso de uso nem sequer depende de {@code InstrucaoEfetivacaoCorePort} ou
 * {@code EntregasEfetivacaoPort} para escrita), TX-A -&gt; HTTP fora de TX -&gt; TX-B unica, e o
 * mapeamento exaustivo e normativo do resultado de {@code RegistrarResultadoEfetivacao} sobre a
 * terminalizacao da propria agenda de reconciliacao (guardrail do Owner).
 */
class ReconciliarEfetivacoesTest {

    private static final EfetivacaoId EFETIVACAO_ID = new EfetivacaoId(UUID.randomUUID());
    private static final SolicitacaoId SOLICITACAO_ID = new SolicitacaoId(UUID.randomUUID());
    private static final long LIMITE_SOLICITADO_CONGELADO = 600_000L;
    private static final Instant T0 = Instant.parse("2026-09-05T12:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration JANELA = Duration.ofMinutes(10);
    private static final Duration BACKOFF_LONGO = Duration.ofMinutes(5);

    private static final TransacaoPort TRANSACAO_PASSA_DIRETO = new TransacaoPort() {
        @Override
        public <T> T executar(Supplier<T> unidade) {
            return unidade.get();
        }
    };

    private static PoliticaRetryEntrega politicaRetrySemJitter() {
        return new PoliticaRetryEntrega(Duration.ofSeconds(30), Duration.ofMinutes(2), 0.0, new Random());
    }

    private static ReconciliarEfetivacoes usecase(
            FakeReconciliacaoEfetivacaoPort reconciliacao, FakeConsultaStatusEfetivacaoCorePort core,
            FakeResultadoEfetivacaoPort resultado, FakeAlertaOperacionalPort alerta, ClockMutavel clock) {
        RegistrarResultadoEfetivacao registrar = new RegistrarResultadoEfetivacao(
                resultado, new EntregasEfetivacaoPortNuncaUsada(), TRANSACAO_PASSA_DIRETO);
        return new ReconciliarEfetivacoes(
                reconciliacao, core, registrar, alerta, TRANSACAO_PASSA_DIRETO, politicaRetrySemJitter(), clock, LEASE, BACKOFF_LONGO);
    }

    private static FakeReconciliacaoEfetivacaoPort reconciliacaoPendente(
            Optional<ProtocoloCore> protocoloConhecido, FakeResultadoEfetivacaoPort resultado, Instant janelaExpiraEm) {
        return new FakeReconciliacaoEfetivacaoPort(protocoloConhecido, () -> resultado.status, janelaExpiraEm);
    }

    // --- Consulta por protocolo vs EfetivacaoId --------------------------------------------------

    @Test
    void protocoloConhecido_consultaPorProtocolo_nuncaPorEfetivacaoId() {
        FakeResultadoEfetivacaoPort resultado = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        FakeReconciliacaoEfetivacaoPort reconciliacao =
                reconciliacaoPendente(Optional.of(new ProtocoloCore("PRT-1")), resultado, T0.plus(JANELA));
        FakeConsultaStatusEfetivacaoCorePort core = new FakeConsultaStatusEfetivacaoCorePort(
                new ResultadoConsultaStatusCore.EmProcessamento());
        FakeAlertaOperacionalPort alerta = new FakeAlertaOperacionalPort();
        ClockMutavel clock = new ClockMutavel(T0);

        usecase(reconciliacao, core, resultado, alerta, clock).executarUmCiclo();

        assertThat(core.chamadasPorProtocolo).isEqualTo(1);
        assertThat(core.chamadasPorEfetivacaoId).isZero();
    }

    @Test
    void protocoloDesconhecido_consultaPorEfetivacaoId_nuncaPorProtocolo() {
        FakeResultadoEfetivacaoPort resultado = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        FakeReconciliacaoEfetivacaoPort reconciliacao = reconciliacaoPendente(Optional.empty(), resultado, T0.plus(JANELA));
        FakeConsultaStatusEfetivacaoCorePort core = new FakeConsultaStatusEfetivacaoCorePort(
                new ResultadoConsultaStatusCore.EmProcessamento());
        FakeAlertaOperacionalPort alerta = new FakeAlertaOperacionalPort();
        ClockMutavel clock = new ClockMutavel(T0);

        usecase(reconciliacao, core, resultado, alerta, clock).executarUmCiclo();

        assertThat(core.chamadasPorEfetivacaoId).isEqualTo(1);
        assertThat(core.chamadasPorProtocolo).isZero();
    }

    // --- Nenhuma pendente / ja terminal ao reclamar (TX-A) ---------------------------------------

    @Test
    void semPendente_naoConsultaOCore() {
        FakeResultadoEfetivacaoPort resultado = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        FakeReconciliacaoEfetivacaoPort reconciliacao = reconciliacaoPendente(Optional.empty(), resultado, T0.plus(JANELA));
        reconciliacao.proximaConsultaEm = T0.plusSeconds(10); // ainda nao devido
        FakeConsultaStatusEfetivacaoCorePort core = new FakeConsultaStatusEfetivacaoCorePort();
        ClockMutavel clock = new ClockMutavel(T0);

        ResultadoCicloReconciliacao ciclo = usecase(reconciliacao, core, resultado, new FakeAlertaOperacionalPort(), clock).executarUmCiclo();

        assertThat(ciclo).isInstanceOf(ResultadoCicloReconciliacao.SemPendente.class);
        assertThat(core.chamadasPorEfetivacaoId + core.chamadasPorProtocolo).isZero();
    }

    @Test
    void jaTerminalAoReclamar_terminalizaReconciliacaoSemConsultarOCore() {
        FakeResultadoEfetivacaoPort resultado = FakeResultadoEfetivacaoPort.terminalEfetivada("PRT-1");
        FakeReconciliacaoEfetivacaoPort reconciliacao = reconciliacaoPendente(Optional.of(new ProtocoloCore("PRT-1")), resultado, T0.plus(JANELA));
        FakeConsultaStatusEfetivacaoCorePort core = new FakeConsultaStatusEfetivacaoCorePort();
        ClockMutavel clock = new ClockMutavel(T0);

        ResultadoCicloReconciliacao ciclo = usecase(reconciliacao, core, resultado, new FakeAlertaOperacionalPort(), clock).executarUmCiclo();

        assertThat(ciclo).isInstanceOf(ResultadoCicloReconciliacao.JaTerminalAoReclamar.class);
        assertThat(core.chamadasPorEfetivacaoId + core.chamadasPorProtocolo).isZero();
        assertThat(reconciliacao.statusReconciliacao).isEqualTo("CONCLUIDA");
    }

    // --- O resultado de RegistrarResultadoEfetivacao governa a terminalizacao -------------------

    @Test
    void coreRespondeEfetivada_concluiEterminalizaReconciliacao_comAutorReconciliacao() {
        FakeResultadoEfetivacaoPort resultado = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        FakeReconciliacaoEfetivacaoPort reconciliacao = reconciliacaoPendente(Optional.empty(), resultado, T0.plus(JANELA));
        FakeConsultaStatusEfetivacaoCorePort core = new FakeConsultaStatusEfetivacaoCorePort(
                new ResultadoConsultaStatusCore.Efetivada(new ProtocoloCore("PRT-1"), LIMITE_SOLICITADO_CONGELADO));
        ClockMutavel clock = new ClockMutavel(T0);

        ResultadoCicloReconciliacao ciclo = usecase(reconciliacao, core, resultado, new FakeAlertaOperacionalPort(), clock).executarUmCiclo();

        assertThat(ciclo).isInstanceOf(ResultadoCicloReconciliacao.ConcluidaPorResultadoAutoritativo.class);
        assertThat(resultado.status).isEqualTo(StatusSolicitacaoAumentoLimite.EFETIVADA);
        assertThat(resultado.autorRecebido).isEqualTo(AtorSistema.RECONCILIACAO_EFETIVACAO);
        assertThat(reconciliacao.statusReconciliacao).isEqualTo("CONCLUIDA");
    }

    @Test
    void coreRespondeFalhaDefinitiva_concluiComFalhaEfetivacao_eTerminalizaReconciliacao() {
        FakeResultadoEfetivacaoPort resultado = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        FakeReconciliacaoEfetivacaoPort reconciliacao = reconciliacaoPendente(Optional.of(new ProtocoloCore("PRT-1")), resultado, T0.plus(JANELA));
        FakeConsultaStatusEfetivacaoCorePort core = new FakeConsultaStatusEfetivacaoCorePort(
                new ResultadoConsultaStatusCore.FalhaDefinitiva(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE));
        ClockMutavel clock = new ClockMutavel(T0);

        ResultadoCicloReconciliacao ciclo = usecase(reconciliacao, core, resultado, new FakeAlertaOperacionalPort(), clock).executarUmCiclo();

        assertThat(ciclo).isInstanceOf(ResultadoCicloReconciliacao.ConcluidaPorResultadoAutoritativo.class);
        assertThat(resultado.status).isEqualTo(StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO);
        assertThat(reconciliacao.statusReconciliacao).isEqualTo("CONCLUIDA");
    }

    /** Adversarial (a) do mapeamento (exigido pelo Owner): Efetivada mas protocolo diverge -- nao termina nada. */
    @Test
    void coreRespondeEfetivadaComProtocoloDivergente_naoConcluiENaoTerminalizaReconciliacao() {
        FakeResultadoEfetivacaoPort resultado = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        resultado.protocoloCore = "PRT-ORIGINAL";
        FakeReconciliacaoEfetivacaoPort reconciliacao = reconciliacaoPendente(Optional.of(new ProtocoloCore("PRT-ORIGINAL")), resultado, T0.plus(JANELA));
        FakeConsultaStatusEfetivacaoCorePort core = new FakeConsultaStatusEfetivacaoCorePort(
                new ResultadoConsultaStatusCore.Efetivada(new ProtocoloCore("PRT-DIVERGENTE"), LIMITE_SOLICITADO_CONGELADO));
        ClockMutavel clock = new ClockMutavel(T0);

        ResultadoCicloReconciliacao ciclo = usecase(reconciliacao, core, resultado, new FakeAlertaOperacionalPort(), clock).executarUmCiclo();

        assertThat(ciclo).isInstanceOf(ResultadoCicloReconciliacao.ReagendadaPorResultadoIncoerente.class);
        assertThat(resultado.status).isEqualTo(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        assertThat(reconciliacao.statusReconciliacao).isEqualTo("PENDENTE");
    }

    /** Adversarial (b): Efetivada com limite incoerente -- nao termina nada, nenhuma conclusao falsa. */
    @Test
    void coreRespondeEfetivadaComLimiteIncoerente_naoConcluiENaoTerminalizaReconciliacao() {
        FakeResultadoEfetivacaoPort resultado = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        FakeReconciliacaoEfetivacaoPort reconciliacao = reconciliacaoPendente(Optional.empty(), resultado, T0.plus(JANELA));
        FakeConsultaStatusEfetivacaoCorePort core = new FakeConsultaStatusEfetivacaoCorePort(
                new ResultadoConsultaStatusCore.Efetivada(new ProtocoloCore("PRT-1"), LIMITE_SOLICITADO_CONGELADO + 1));
        ClockMutavel clock = new ClockMutavel(T0);

        ResultadoCicloReconciliacao ciclo = usecase(reconciliacao, core, resultado, new FakeAlertaOperacionalPort(), clock).executarUmCiclo();

        assertThat(ciclo).isInstanceOf(ResultadoCicloReconciliacao.ReagendadaPorResultadoIncoerente.class);
        assertThat(resultado.status).isEqualTo(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        assertThat(reconciliacao.statusReconciliacao).isEqualTo("PENDENTE");
    }

    /**
     * Adversarial (c): a solicitacao JA e terminal quando a resposta do Core chega, mas o CLAIM
     * (TX-A) a reclamou quando ainda nao era -- uma leitura nao travada, anterior a um callback
     * concorrente que concluiu no meio do caminho (HTTP fora de TX). TX-B descobre o terminal
     * verdadeiro em {@code RegistrarResultadoEfetivacao#executar}, nao em TX-A: terminal permanece
     * (nunca reescrito), a reconciliacao ainda assim pode concluir, e a contradicao e anomalia
     * observavel -- exatamente o cenario que {@code statusSolicitacaoAtual} desacoplado de
     * {@code resultado.status} no momento do claim reproduz.
     */
    @Test
    void terminalLocalJaExistente_respostaContraditoria_terminalPermaneceEReconciliacaoConclui() {
        FakeResultadoEfetivacaoPort resultado = FakeResultadoEfetivacaoPort.terminalEfetivada("PRT-1");
        FakeReconciliacaoEfetivacaoPort reconciliacao = new FakeReconciliacaoEfetivacaoPort(
                Optional.of(new ProtocoloCore("PRT-1")),
                () -> StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO, // snapshot de TX-A, anterior a conclusao concorrente
                T0.plus(JANELA));
        FakeConsultaStatusEfetivacaoCorePort core = new FakeConsultaStatusEfetivacaoCorePort(
                new ResultadoConsultaStatusCore.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_INEXISTENTE));
        ClockMutavel clock = new ClockMutavel(T0);

        ResultadoCicloReconciliacao ciclo = usecase(reconciliacao, core, resultado, new FakeAlertaOperacionalPort(), clock).executarUmCiclo();

        assertThat(ciclo).isInstanceOf(ResultadoCicloReconciliacao.ConcluidaPorOutroCaminho.class);
        assertThat(((ResultadoCicloReconciliacao.ConcluidaPorOutroCaminho) ciclo).contraditoria()).isTrue();
        assertThat(resultado.status).isEqualTo(StatusSolicitacaoAumentoLimite.EFETIVADA); // terminal preservado
        assertThat(reconciliacao.statusReconciliacao).isEqualTo("CONCLUIDA");
    }

    // --- Sem resultado autoritativo: reagenda dentro da janela; indetermina quando esgota --------

    @Test
    void desconhecida_reagendaComBackoffCurto_nuncaConclui() {
        FakeResultadoEfetivacaoPort resultado = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        FakeReconciliacaoEfetivacaoPort reconciliacao = reconciliacaoPendente(Optional.empty(), resultado, T0.plus(JANELA));
        FakeConsultaStatusEfetivacaoCorePort core = new FakeConsultaStatusEfetivacaoCorePort(new ResultadoConsultaStatusCore.Desconhecida());
        ClockMutavel clock = new ClockMutavel(T0);

        ResultadoCicloReconciliacao ciclo = usecase(reconciliacao, core, resultado, new FakeAlertaOperacionalPort(), clock).executarUmCiclo();

        assertThat(ciclo).isInstanceOf(ResultadoCicloReconciliacao.ReagendadaSemResultadoAutoritativo.class);
        assertThat(resultado.status).isEqualTo(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        assertThat(reconciliacao.statusReconciliacao).isEqualTo("PENDENTE");
        assertThat(reconciliacao.proximaConsultaEm).isAfter(T0);
        assertThat(reconciliacao.indeterminadaEm).isNull();
    }

    @Test
    void janelaEsgotada_indeterminaAgora_disparaAlertaUmaUnicaVez() {
        FakeResultadoEfetivacaoPort resultado = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        FakeReconciliacaoEfetivacaoPort reconciliacao = reconciliacaoPendente(Optional.empty(), resultado, T0.plus(JANELA));
        FakeConsultaStatusEfetivacaoCorePort core = new FakeConsultaStatusEfetivacaoCorePort(new ResultadoConsultaStatusCore.EmProcessamento());
        FakeAlertaOperacionalPort alerta = new FakeAlertaOperacionalPort();
        ClockMutavel clock = new ClockMutavel(T0.plus(JANELA)); // janela ja expirada no momento do ciclo

        ResultadoCicloReconciliacao ciclo = usecase(reconciliacao, core, resultado, alerta, clock).executarUmCiclo();

        assertThat(ciclo).isInstanceOf(ResultadoCicloReconciliacao.IndeterminadaAgora.class);
        assertThat(resultado.status).isEqualTo(StatusSolicitacaoAumentoLimite.EFETIVACAO_INDETERMINADA);
        assertThat(alerta.chamadas).isEqualTo(1);
        assertThat(reconciliacao.statusReconciliacao).isEqualTo("PENDENTE"); // indeterminada NAO e concluida
        assertThat(reconciliacao.indeterminadaEm).isNotNull();
        assertThat(reconciliacao.proximaConsultaEm).isEqualTo(clock.instant().plus(BACKOFF_LONGO));
    }

    @Test
    void jaIndeterminada_novoCicloSemResultado_naoDisparaNovoAlerta_usaBackoffLongo() {
        FakeResultadoEfetivacaoPort resultado = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.EFETIVACAO_INDETERMINADA);
        FakeReconciliacaoEfetivacaoPort reconciliacao = reconciliacaoPendente(Optional.empty(), resultado, T0.plus(JANELA));
        reconciliacao.indeterminadaEm = T0.plus(JANELA); // ja indeterminada de um ciclo anterior
        FakeConsultaStatusEfetivacaoCorePort core = new FakeConsultaStatusEfetivacaoCorePort(new ResultadoConsultaStatusCore.EmProcessamento());
        FakeAlertaOperacionalPort alerta = new FakeAlertaOperacionalPort();
        Instant agora = T0.plus(JANELA).plus(BACKOFF_LONGO);
        ClockMutavel clock = new ClockMutavel(agora);

        ResultadoCicloReconciliacao ciclo = usecase(reconciliacao, core, resultado, alerta, clock).executarUmCiclo();

        assertThat(ciclo).isInstanceOf(ResultadoCicloReconciliacao.JaEstavaIndeterminada.class);
        assertThat(alerta.chamadas).isZero();
        assertThat(reconciliacao.proximaConsultaEm).isEqualTo(agora.plus(BACKOFF_LONGO));
    }

    /**
     * Achado do /code-review: uma resposta autoritativa INCOERENTE (protocolo divergente) chegando
     * NA FASE ja indeterminada nao pode ser descartada silenciosamente -- e uma anomalia observavel
     * por si so (mesmo papel de {@code contraditoria} em {@code ConcluidaPorOutroCaminho}), mesmo
     * que o polling de baixa frequencia continue exatamente igual ao caso sem incoerencia.
     */
    @Test
    void jaIndeterminada_respostaAutoritativaIncoerenteChega_marcaIncoerenteMasContinuaIndeterminada() {
        FakeResultadoEfetivacaoPort resultado = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.EFETIVACAO_INDETERMINADA);
        resultado.protocoloCore = "PRT-ORIGINAL";
        FakeReconciliacaoEfetivacaoPort reconciliacao = reconciliacaoPendente(Optional.of(new ProtocoloCore("PRT-ORIGINAL")), resultado, T0.plus(JANELA));
        reconciliacao.indeterminadaEm = T0.plus(JANELA);
        FakeConsultaStatusEfetivacaoCorePort core = new FakeConsultaStatusEfetivacaoCorePort(
                new ResultadoConsultaStatusCore.Efetivada(new ProtocoloCore("PRT-DIVERGENTE"), LIMITE_SOLICITADO_CONGELADO));
        Instant agora = T0.plus(JANELA).plus(BACKOFF_LONGO);
        ClockMutavel clock = new ClockMutavel(agora);

        ResultadoCicloReconciliacao ciclo = usecase(reconciliacao, core, resultado, new FakeAlertaOperacionalPort(), clock).executarUmCiclo();

        assertThat(ciclo).isInstanceOf(ResultadoCicloReconciliacao.JaEstavaIndeterminada.class);
        assertThat(((ResultadoCicloReconciliacao.JaEstavaIndeterminada) ciclo).incoerente()).isTrue();
        assertThat(resultado.status).isEqualTo(StatusSolicitacaoAumentoLimite.EFETIVACAO_INDETERMINADA); // nunca transiciona
        assertThat(reconciliacao.proximaConsultaEm).isEqualTo(agora.plus(BACKOFF_LONGO));
    }

    /**
     * Ciclo completo pos-indeterminacao (exigido pelo Owner): AGUARDANDO -> janela expira ->
     * EFETIVACAO_INDETERMINADA -> proxima consulta usa backoff-longo -> Core continua
     * EmProcessamento -> segue INDETERMINADA e usa novamente backoff-longo -> resultado
     * autoritativo posterior -> terminal de negocio -> reconciliacao CONCLUIDA.
     */
    @Test
    void cicloCompletoPosIndeterminacao_ateConclusaoAutoritativa() {
        FakeResultadoEfetivacaoPort resultado = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        FakeReconciliacaoEfetivacaoPort reconciliacao = reconciliacaoPendente(Optional.empty(), resultado, T0.plus(JANELA));
        FakeAlertaOperacionalPort alerta = new FakeAlertaOperacionalPort();
        ClockMutavel clock = new ClockMutavel(T0.plus(JANELA));

        // 1) janela expira -- indeterminada agora, alerta dispara.
        FakeConsultaStatusEfetivacaoCorePort core1 = new FakeConsultaStatusEfetivacaoCorePort(new ResultadoConsultaStatusCore.EmProcessamento());
        ResultadoCicloReconciliacao ciclo1 = usecase(reconciliacao, core1, resultado, alerta, clock).executarUmCiclo();
        assertThat(ciclo1).isInstanceOf(ResultadoCicloReconciliacao.IndeterminadaAgora.class);
        assertThat(alerta.chamadas).isEqualTo(1);

        // 2) proximo ciclo, ja com backoff-longo decorrido: ainda EmProcessamento -- segue indeterminada, sem novo alerta.
        clock.avancar(BACKOFF_LONGO);
        FakeConsultaStatusEfetivacaoCorePort core2 = new FakeConsultaStatusEfetivacaoCorePort(new ResultadoConsultaStatusCore.EmProcessamento());
        ResultadoCicloReconciliacao ciclo2 = usecase(reconciliacao, core2, resultado, alerta, clock).executarUmCiclo();
        assertThat(ciclo2).isInstanceOf(ResultadoCicloReconciliacao.JaEstavaIndeterminada.class);
        assertThat(alerta.chamadas).isEqualTo(1);
        assertThat(reconciliacao.proximaConsultaEm).isEqualTo(clock.instant().plus(BACKOFF_LONGO));

        // 3) resultado autoritativo finalmente chega -- conclui e a reconciliacao vira CONCLUIDA.
        clock.avancar(BACKOFF_LONGO);
        FakeConsultaStatusEfetivacaoCorePort core3 = new FakeConsultaStatusEfetivacaoCorePort(
                new ResultadoConsultaStatusCore.Efetivada(new ProtocoloCore("PRT-TARDIO"), LIMITE_SOLICITADO_CONGELADO));
        ResultadoCicloReconciliacao ciclo3 = usecase(reconciliacao, core3, resultado, alerta, clock).executarUmCiclo();
        assertThat(ciclo3).isInstanceOf(ResultadoCicloReconciliacao.ConcluidaPorResultadoAutoritativo.class);
        assertThat(resultado.status).isEqualTo(StatusSolicitacaoAumentoLimite.EFETIVADA);
        assertThat(reconciliacao.statusReconciliacao).isEqualTo("CONCLUIDA");
        assertThat(alerta.chamadas).isEqualTo(1); // nenhum alerta adicional na conclusao
    }

    /** Conclusao tardia em FALHA autoritativa apos indeterminada -- equivalente exigido pelo AC16. */
    @Test
    void jaIndeterminada_falhaDefinitivaChegaDepois_concluiComFalhaEfetivacao() {
        FakeResultadoEfetivacaoPort resultado = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.EFETIVACAO_INDETERMINADA);
        FakeReconciliacaoEfetivacaoPort reconciliacao = reconciliacaoPendente(Optional.of(new ProtocoloCore("PRT-1")), resultado, T0.plus(JANELA));
        reconciliacao.indeterminadaEm = T0.plus(JANELA);
        FakeConsultaStatusEfetivacaoCorePort core = new FakeConsultaStatusEfetivacaoCorePort(
                new ResultadoConsultaStatusCore.FalhaDefinitiva(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE));
        ClockMutavel clock = new ClockMutavel(T0.plus(JANELA).plus(BACKOFF_LONGO));

        ResultadoCicloReconciliacao ciclo = usecase(reconciliacao, core, resultado, new FakeAlertaOperacionalPort(), clock).executarUmCiclo();

        assertThat(ciclo).isInstanceOf(ResultadoCicloReconciliacao.ConcluidaPorResultadoAutoritativo.class);
        assertThat(resultado.status).isEqualTo(StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO);
        assertThat(reconciliacao.statusReconciliacao).isEqualTo("CONCLUIDA");
    }

    // --- Fencing (TX-B) ---------------------------------------------------------------------------

    @Test
    void claimObsoletoNaTxB_descartaSemNenhumaEscrita() {
        FakeResultadoEfetivacaoPort resultado = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        FakeReconciliacaoEfetivacaoPort reconciliacao = new FakeReconciliacaoEfetivacaoPort(
                Optional.empty(), () -> resultado.status, T0.plus(JANELA)) {
            @Override
            public boolean claimAindaValido(EfetivacaoReconciliacaoReclamada claim) {
                return false;
            }
        };
        FakeConsultaStatusEfetivacaoCorePort core = new FakeConsultaStatusEfetivacaoCorePort(
                new ResultadoConsultaStatusCore.Efetivada(new ProtocoloCore("PRT-1"), LIMITE_SOLICITADO_CONGELADO));
        ClockMutavel clock = new ClockMutavel(T0);

        ResultadoCicloReconciliacao ciclo = usecase(reconciliacao, core, resultado, new FakeAlertaOperacionalPort(), clock).executarUmCiclo();

        assertThat(ciclo).isInstanceOf(ResultadoCicloReconciliacao.DescartadoPorFencing.class);
        assertThat(resultado.chamadasDeRegistro).isZero();
    }

    // --- Fakes -------------------------------------------------------------------------------------

    private static final class ClockMutavel extends Clock {
        private Instant agora;

        ClockMutavel(Instant inicial) {
            this.agora = inicial;
        }

        void avancar(Duration duracao) {
            agora = agora.plus(duracao);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("nao usado neste teste");
        }

        @Override
        public Instant instant() {
            return agora;
        }
    }

    private static class FakeReconciliacaoEfetivacaoPort implements ReconciliacaoEfetivacaoPort {
        private final Optional<ProtocoloCore> protocoloConhecido;
        private final Supplier<StatusSolicitacaoAumentoLimite> statusSolicitacaoAtual;
        Instant janelaExpiraEm;
        Instant proximaConsultaEm = Instant.EPOCH;
        Instant indeterminadaEm;
        String statusReconciliacao = "PENDENTE";
        UUID claimAtual;
        int tentativas;

        FakeReconciliacaoEfetivacaoPort(
                Optional<ProtocoloCore> protocoloConhecido, Supplier<StatusSolicitacaoAumentoLimite> statusSolicitacaoAtual,
                Instant janelaExpiraEm) {
            this.protocoloConhecido = protocoloConhecido;
            this.statusSolicitacaoAtual = statusSolicitacaoAtual;
            this.janelaExpiraEm = janelaExpiraEm;
        }

        @Override
        public ReclamacaoReconciliacao reclamarProxima(Instant agora, Duration lease) {
            if (!"PENDENTE".equals(statusReconciliacao) || proximaConsultaEm.isAfter(agora)) {
                return new ReclamacaoReconciliacao.NenhumaPendente();
            }
            StatusSolicitacaoAumentoLimite statusSolicitacao = statusSolicitacaoAtual.get();
            if (statusSolicitacao.isTerminal()) {
                statusReconciliacao = "CONCLUIDA";
                claimAtual = null;
                return new ReclamacaoReconciliacao.JaTerminalDescartada(statusSolicitacao);
            }
            claimAtual = UUID.randomUUID();
            tentativas++;
            return new ReclamacaoReconciliacao.Reclamada(new EfetivacaoReconciliacaoReclamada(
                    claimAtual, EFETIVACAO_ID, SOLICITACAO_ID, protocoloConhecido, indeterminadaEm != null, janelaExpiraEm, tentativas));
        }

        @Override
        public boolean claimAindaValido(EfetivacaoReconciliacaoReclamada claim) {
            return "PENDENTE".equals(statusReconciliacao) && claim.claimId().equals(claimAtual);
        }

        @Override
        public void terminalizar(EfetivacaoReconciliacaoReclamada claim, Instant agora) {
            statusReconciliacao = "CONCLUIDA";
            claimAtual = null;
        }

        @Override
        public void reagendar(EfetivacaoReconciliacaoReclamada claim, Instant proximaConsultaEm, Instant agora) {
            this.proximaConsultaEm = proximaConsultaEm;
            claimAtual = null;
        }

        @Override
        public void reagendarAposIndeterminacao(EfetivacaoReconciliacaoReclamada claim, Instant proximaConsultaEm, Instant agora) {
            this.proximaConsultaEm = proximaConsultaEm;
            if (indeterminadaEm == null) {
                indeterminadaEm = agora;
            }
            claimAtual = null;
        }
    }

    private static final class FakeConsultaStatusEfetivacaoCorePort implements ConsultaStatusEfetivacaoCorePort {
        private final Deque<ResultadoConsultaStatusCore> respostas;
        int chamadasPorProtocolo = 0;
        int chamadasPorEfetivacaoId = 0;

        FakeConsultaStatusEfetivacaoCorePort(ResultadoConsultaStatusCore... respostas) {
            this.respostas = new ArrayDeque<>(List.of(respostas));
        }

        @Override
        public ResultadoConsultaStatusCore consultarPorProtocolo(ProtocoloCore protocolo) {
            chamadasPorProtocolo++;
            return proxima();
        }

        @Override
        public ResultadoConsultaStatusCore consultarPorEfetivacaoId(EfetivacaoId efetivacaoId) {
            chamadasPorEfetivacaoId++;
            return proxima();
        }

        private ResultadoConsultaStatusCore proxima() {
            return respostas.isEmpty()
                    ? new ResultadoConsultaStatusCore.Indeterminada("sem mais respostas configuradas")
                    : respostas.poll();
        }
    }

    private static final class FakeAlertaOperacionalPort implements AlertaOperacionalPort {
        int chamadas = 0;

        @Override
        public void efetivacaoIndeterminada(EfetivacaoId efetivacaoId, SolicitacaoId solicitacaoId, Instant ocorridoEm) {
            chamadas++;
        }
    }

    /**
     * Mesma disciplina de {@code RegistrarResultadoEfetivacaoTest}: delega ao dominio real e
     * reproduz a classificacao terminal em tres eixos que {@code JpaResultadoEfetivacaoAdapter}
     * implementa contra PostgreSQL (S3). Registra tambem o ULTIMO autor recebido, para provar que
     * a reconciliacao sempre informa {@code AtorSistema.RECONCILIACAO_EFETIVACAO} (AC12).
     */
    private static final class FakeResultadoEfetivacaoPort implements ResultadoEfetivacaoPort {
        private static final Instant DECIDIDA_EM = T0.minus(Duration.ofMinutes(15));

        StatusSolicitacaoAumentoLimite status;
        String protocoloCore;
        MotivoFalhaEfetivacao motivoFalha;
        int chamadasDeRegistro = 0;
        AtorOperacao autorRecebido;

        private FakeResultadoEfetivacaoPort(StatusSolicitacaoAumentoLimite status, String protocoloCore, MotivoFalhaEfetivacao motivoFalha) {
            this.status = status;
            this.protocoloCore = protocoloCore;
            this.motivoFalha = motivoFalha;
        }

        static FakeResultadoEfetivacaoPort naoTerminal(StatusSolicitacaoAumentoLimite status) {
            return new FakeResultadoEfetivacaoPort(status, null, null);
        }

        static FakeResultadoEfetivacaoPort terminalEfetivada(String protocoloCore) {
            return new FakeResultadoEfetivacaoPort(StatusSolicitacaoAumentoLimite.EFETIVADA, protocoloCore, null);
        }

        @Override
        public ResultadoRegistroEfetivacao registrar(
                EfetivacaoId efetivacaoId, ResultadoEfetivacaoRecebido resultado, Optional<ProtocoloCore> protocoloInformado,
                AtorOperacao autor, Instant agora) {
            chamadasDeRegistro++;
            autorRecebido = autor;

            boolean protocoloCoerente = protocoloCore == null || protocoloInformado.isEmpty()
                    || protocoloCore.equals(protocoloInformado.get().valor());

            if (status.isTerminal()) {
                boolean resultadoCoerente = switch (resultado) {
                    case ResultadoEfetivacaoRecebido.FalhaDefinitiva falha ->
                            status == StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO && falha.motivo() == motivoFalha;
                    case ResultadoEfetivacaoRecebido.Sucesso sucesso ->
                            status == StatusSolicitacaoAumentoLimite.EFETIVADA
                                    && sucesso.limiteEfetivadoCentavos() == LIMITE_SOLICITADO_CONGELADO;
                };
                return protocoloCoerente && resultadoCoerente
                        ? new ResultadoRegistroEfetivacao.JaTerminalIdentica(status)
                        : new ResultadoRegistroEfetivacao.JaTerminalContraditoria(status);
            }

            if (!protocoloCoerente) {
                return new ResultadoRegistroEfetivacao.ProtocoloDivergente();
            }
            if (resultado instanceof ResultadoEfetivacaoRecebido.Sucesso sucesso
                    && sucesso.limiteEfetivadoCentavos() != LIMITE_SOLICITADO_CONGELADO) {
                return new ResultadoRegistroEfetivacao.SucessoIncoerente();
            }
            if (protocoloInformado.isPresent() && protocoloCore == null) {
                protocoloCore = protocoloInformado.get().valor();
            }

            SolicitacaoAumentoLimite solicitacao = new SolicitacaoAumentoLimite(status);
            StatusSolicitacaoAumentoLimite statusResultante = switch (resultado) {
                case ResultadoEfetivacaoRecebido.FalhaDefinitiva falha -> {
                    motivoFalha = falha.motivo();
                    yield solicitacao.transicionarPara(StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO).status();
                }
                case ResultadoEfetivacaoRecebido.Sucesso ignored ->
                        solicitacao.transicionarPara(StatusSolicitacaoAumentoLimite.EFETIVADA).status();
            };
            status = statusResultante;
            return new ResultadoRegistroEfetivacao.Concluida(statusResultante, Duration.between(DECIDIDA_EM, agora));
        }

        @Override
        public ResultadoIndeterminacao registrarIndeterminacao(EfetivacaoId efetivacaoId, Instant agora) {
            autorRecebido = AtorSistema.RECONCILIACAO_EFETIVACAO;
            if (status.isTerminal()) {
                return new ResultadoIndeterminacao.JaTerminal(status);
            }
            if (status == StatusSolicitacaoAumentoLimite.EFETIVACAO_INDETERMINADA) {
                return new ResultadoIndeterminacao.JaEstavaIndeterminada();
            }
            status = new SolicitacaoAumentoLimite(status)
                    .transicionarPara(StatusSolicitacaoAumentoLimite.EFETIVACAO_INDETERMINADA)
                    .status();
            return new ResultadoIndeterminacao.IndeterminadaAgora();
        }
    }

    /** {@code executarSobClaim} nunca e alcancado pela reconciliacao -- so o dispatcher o usa. */
    private static final class EntregasEfetivacaoPortNuncaUsada implements EntregasEfetivacaoPort {
        @Override
        public ReclamacaoEntrega reclamarProxima(Instant agora, int maxTentativas, Duration lease) {
            throw new UnsupportedOperationException("nao usado pela reconciliacao");
        }

        @Override
        public ResultadoRegistroEntrega registrarAceite(EntregaEfetivacaoReclamada claim, ProtocoloCore protocoloCore, Instant agora) {
            throw new UnsupportedOperationException("nao usado pela reconciliacao");
        }

        @Override
        public ResultadoRegistroEntrega reagendar(
                EntregaEfetivacaoReclamada claim, Instant proximaTentativaEm, String erroSanitizado, Instant agora) {
            throw new UnsupportedOperationException("nao usado pela reconciliacao");
        }

        @Override
        public ResultadoRegistroEntrega marcarIndeterminada(EntregaEfetivacaoReclamada claim, String erroSanitizado, Instant agora) {
            throw new UnsupportedOperationException("nao usado pela reconciliacao");
        }

        @Override
        public boolean claimAindaValido(EntregaEfetivacaoReclamada claim) {
            throw new UnsupportedOperationException("nao usado pela reconciliacao");
        }

        @Override
        public void terminalizarPorFalhaDefinitiva(EntregaEfetivacaoReclamada claim, Instant agora) {
            throw new UnsupportedOperationException("nao usado pela reconciliacao");
        }

        @Override
        public void terminalizarPorConclusaoConcorrente(
                EntregaEfetivacaoReclamada claim, StatusSolicitacaoAumentoLimite terminalObservado, Instant agora) {
            throw new UnsupportedOperationException("nao usado pela reconciliacao");
        }
    }
}
