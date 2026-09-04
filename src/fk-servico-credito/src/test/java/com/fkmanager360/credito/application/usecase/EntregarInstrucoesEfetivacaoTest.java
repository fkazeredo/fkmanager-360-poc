package com.fkmanager360.credito.application.usecase;

import com.fkmanager360.credito.application.PoliticaRetryEntrega;
import com.fkmanager360.credito.application.ResultadoEpisodioEntrega;
import com.fkmanager360.credito.application.port.out.EntregaEfetivacaoReclamada;
import com.fkmanager360.credito.application.port.out.EntregasEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.InstrucaoEfetivacaoCorePort;
import com.fkmanager360.credito.application.port.out.IntencaoEfetivacao;
import com.fkmanager360.credito.application.port.out.ReclamacaoEntrega;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoRecebido;
import com.fkmanager360.credito.application.port.out.ResultadoInstrucaoCore;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroEfetivacao;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroEntrega;
import com.fkmanager360.credito.application.port.out.TransacaoPort;
import com.fkmanager360.credito.domain.AtorOperacao;
import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.CorrelationId;
import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.LimiteChequeEspecialVigente;
import com.fkmanager360.credito.domain.LimiteSolicitado;
import com.fkmanager360.credito.domain.MotivoFalhaEfetivacao;
import com.fkmanager360.credito.domain.ProtocoloCore;
import com.fkmanager360.credito.domain.SolicitacaoId;
import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2 (ADR-0018): {@code EntregarInstrucoesEfetivacao} com fakes comportamentais -- sem Spring, sem
 * Postgres. O fake de {@link EntregasEfetivacaoPort} modela UMA linha de {@code outbox_entrega}
 * em memoria com a MESMA regra de fencing (claimId + status PENDENTE) que
 * {@code JdbcEntregasEfetivacaoAdapter} implementa contra PostgreSQL real (provado em S3); aqui a
 * concorrencia real nao esta em jogo -- o que se prova e a orquestracao do caso de uso diante de
 * cada uma das quatro classes da ACL, incluindo o descarte por fencing (plano #0004, secao 10).
 */
class EntregarInstrucoesEfetivacaoTest {

    private static final Instant AGORA = Instant.parse("2026-09-03T12:00:00Z");
    private static final int MAX_TENTATIVAS = 4;
    private static final Duration LEASE = Duration.ofSeconds(30);

    private final IntencaoEfetivacao intencaoOriginal = new IntencaoEfetivacao(
            new EfetivacaoId(UUID.randomUUID()),
            UUID.randomUUID(),
            new ContaId("10001"),
            new LimiteChequeEspecialVigente(500_000),
            new LimiteSolicitado(600_000),
            new CorrelationId(UUID.randomUUID()));

    @Test
    void executarUmEpisodio_semEntregaPendente_devolveSemPendenteENuncaChamaOCore() {
        FakeEntregasEfetivacaoPort entregas = FakeEntregasEfetivacaoPort.semPendente();
        FakeInstrucaoEfetivacaoCorePort core = new FakeInstrucaoEfetivacaoCorePort();
        EntregarInstrucoesEfetivacao dispatcher = criarDispatcher(entregas, core, new RelogioControlavel(AGORA));

        ResultadoEpisodioEntrega resultado = dispatcher.executarUmEpisodio();

        assertThat(resultado).isInstanceOf(ResultadoEpisodioEntrega.SemPendente.class);
        assertThat(core.chamadas).isEmpty();
    }

    @Test
    void executarUmEpisodio_aceite_persisteProtocoloEPermaneceAguardandoEfetivacao() {
        FakeEntregasEfetivacaoPort entregas = FakeEntregasEfetivacaoPort.comEntregaPendente(intencaoOriginal);
        ProtocoloCore protocolo = new ProtocoloCore("PRT-000000000001");
        FakeInstrucaoEfetivacaoCorePort core = new FakeInstrucaoEfetivacaoCorePort(new ResultadoInstrucaoCore.Aceite(protocolo));
        EntregarInstrucoesEfetivacao dispatcher = criarDispatcher(entregas, core, new RelogioControlavel(AGORA));

        ResultadoEpisodioEntrega resultado = dispatcher.executarUmEpisodio();

        assertThat(resultado).isInstanceOf(ResultadoEpisodioEntrega.Aceite.class);
        assertThat(entregas.status).isEqualTo("ACEITA");
        assertThat(entregas.protocoloRegistrado).isEqualTo(protocolo);
        assertThat(core.chamadas).hasSize(1);
        assertThat(core.chamadas.get(0).efetivacaoId()).isEqualTo(intencaoOriginal.efetivacaoId());
    }

    @Test
    void executarUmEpisodio_falhaTransitoria_reagendaComMesmoEfetivacaoIdEMessageId_ateEsgotarSemFalhaEfetivacao() {
        FakeEntregasEfetivacaoPort entregas = FakeEntregasEfetivacaoPort.comEntregaPendente(intencaoOriginal);
        FakeInstrucaoEfetivacaoCorePort core = new FakeInstrucaoEfetivacaoCorePort(
                new ResultadoInstrucaoCore.FalhaTransitoria("timeout"),
                new ResultadoInstrucaoCore.FalhaTransitoria("timeout"),
                new ResultadoInstrucaoCore.FalhaTransitoria("timeout"),
                new ResultadoInstrucaoCore.FalhaTransitoria("timeout"));
        RelogioControlavel relogio = new RelogioControlavel(AGORA);
        EntregarInstrucoesEfetivacao dispatcher = criarDispatcher(entregas, core, relogio);

        for (int i = 0; i < MAX_TENTATIVAS; i++) {
            entregas.proximaTentativaEm = relogio.instant();
            ResultadoEpisodioEntrega resultado = dispatcher.executarUmEpisodio();
            assertThat(resultado).isInstanceOf(ResultadoEpisodioEntrega.Reagendada.class);
            relogio.avancarPara(entregas.proximaTentativaEm);
        }

        // Tentativas esgotadas: o proximo episodio nao chama o Core de novo, e NUNCA produz FalhaDefinitiva.
        entregas.proximaTentativaEm = relogio.instant();
        ResultadoEpisodioEntrega esgotamento = dispatcher.executarUmEpisodio();

        assertThat(esgotamento).isInstanceOf(ResultadoEpisodioEntrega.EsgotadaAgora.class);
        assertThat(core.chamadas).hasSize(MAX_TENTATIVAS);
        assertThat(core.chamadas).allSatisfy(intencao -> {
            assertThat(intencao.efetivacaoId()).isEqualTo(intencaoOriginal.efetivacaoId());
            assertThat(intencao.messageId()).isEqualTo(intencaoOriginal.messageId());
        });
        assertThat(entregas.status).isEqualTo("ESGOTADA");
    }

    @Test
    void executarUmEpisodio_falhaDefinitiva_convergeEmConcluirComFalhaDefinitiva() {
        FakeEntregasEfetivacaoPort entregas = FakeEntregasEfetivacaoPort.comEntregaPendente(intencaoOriginal);
        FakeInstrucaoEfetivacaoCorePort core = new FakeInstrucaoEfetivacaoCorePort(
                new ResultadoInstrucaoCore.FalhaDefinitiva(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE));
        EntregarInstrucoesEfetivacao dispatcher = criarDispatcher(entregas, core, new RelogioControlavel(AGORA));

        ResultadoEpisodioEntrega resultado = dispatcher.executarUmEpisodio();

        assertThat(resultado).isInstanceOf(ResultadoEpisodioEntrega.FalhaDefinitiva.class);
        ResultadoEpisodioEntrega.FalhaDefinitiva falhaDefinitiva = (ResultadoEpisodioEntrega.FalhaDefinitiva) resultado;
        assertThat(falhaDefinitiva.motivo()).isEqualTo(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE);
        assertThat(falhaDefinitiva.permanenciaEmAguardandoEfetivacao()).isNotNull();
        assertThat(entregas.status).isEqualTo("FALHA_DEFINITIVA");
        assertThat(entregas.motivoFalhaConcluido).isEqualTo(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE);
    }

    @Test
    void executarUmEpisodio_respostaIndeterminada_paraSemConcluirNada() {
        FakeEntregasEfetivacaoPort entregas = FakeEntregasEfetivacaoPort.comEntregaPendente(intencaoOriginal);
        FakeInstrucaoEfetivacaoCorePort core = new FakeInstrucaoEfetivacaoCorePort(
                new ResultadoInstrucaoCore.RespostaIndeterminada("COD-RET desconhecido"));
        EntregarInstrucoesEfetivacao dispatcher = criarDispatcher(entregas, core, new RelogioControlavel(AGORA));

        ResultadoEpisodioEntrega resultado = dispatcher.executarUmEpisodio();

        assertThat(resultado).isInstanceOf(ResultadoEpisodioEntrega.Indeterminada.class);
        assertThat(entregas.status).isEqualTo("INDETERMINADA");
        assertThat(entregas.motivoFalhaConcluido).isNull();
    }

    @Test
    void executarUmEpisodio_claimObsoletoEmQualquerClasse_descartaSemAplicarNada() {
        for (ResultadoInstrucaoCore resultadoCore : List.of(
                new ResultadoInstrucaoCore.Aceite(new ProtocoloCore("PRT-000000000001")),
                new ResultadoInstrucaoCore.FalhaTransitoria("timeout"),
                new ResultadoInstrucaoCore.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_INEXISTENTE),
                new ResultadoInstrucaoCore.RespostaIndeterminada("payload malformado"))) {

            FakeEntregasEfetivacaoPort entregas = FakeEntregasEfetivacaoPort.comEntregaPendente(intencaoOriginal);
            entregas.forcarDescarteNaProximaEscrita = true;
            FakeInstrucaoEfetivacaoCorePort core = new FakeInstrucaoEfetivacaoCorePort(resultadoCore);
            EntregarInstrucoesEfetivacao dispatcher = criarDispatcher(entregas, core, new RelogioControlavel(AGORA));

            ResultadoEpisodioEntrega resultado = dispatcher.executarUmEpisodio();

            assertThat(resultado).isInstanceOf(ResultadoEpisodioEntrega.DescartadaPorFencing.class);
            assertThat(entregas.status).isEqualTo("PENDENTE");
            assertThat(entregas.protocoloRegistrado).isNull();
            assertThat(entregas.motivoFalhaConcluido).isNull();
        }
    }

    /** {@code TransacaoPort} de teste: executa a unidade diretamente (S2 nao prova atomicidade). */
    private static final TransacaoPort TRANSACAO_PASSA_DIRETO = new TransacaoPort() {
        @Override
        public <T> T executar(Supplier<T> unidade) {
            return unidade.get();
        }
    };

    private static EntregarInstrucoesEfetivacao criarDispatcher(
            FakeEntregasEfetivacaoPort entregas, InstrucaoEfetivacaoCorePort core, Clock relogio) {
        PoliticaRetryEntrega politicaRetry = new PoliticaRetryEntrega(
                Duration.ofSeconds(1), Duration.ofSeconds(4), 0.0, new Random(42));
        // Caso de uso de conclusao REAL sobre o mesmo fake (que implementa as duas portas sobre a
        // mesma linha em memoria, espelhando producao: um banco, duas portas).
        RegistrarResultadoEfetivacao registrarResultado =
                new RegistrarResultadoEfetivacao(entregas, entregas, TRANSACAO_PASSA_DIRETO);
        return new EntregarInstrucoesEfetivacao(
                entregas, core, politicaRetry, registrarResultado, relogio, MAX_TENTATIVAS, LEASE);
    }

    /**
     * Relogio mutavel: o caso de uso agora consulta o {@link Clock} duas vezes por episodio (antes
     * do claim e depois do retorno do Core, plano #0004 -- ver Javadoc de
     * {@link EntregarInstrucoesEfetivacao}), entao o teste precisa poder avancar o instante entre
     * chamadas sem reconstruir o dispatcher.
     */
    private static final class RelogioControlavel extends Clock {
        private Instant atual;

        RelogioControlavel(Instant inicial) {
            this.atual = inicial;
        }

        void avancarPara(Instant novoInstante) {
            this.atual = novoInstante;
        }

        @Override
        public Instant instant() {
            return atual;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }

    /** Fake que devolve, em ordem, os resultados configurados -- e registra as intencoes recebidas. */
    private static final class FakeInstrucaoEfetivacaoCorePort implements InstrucaoEfetivacaoCorePort {
        private final Deque<ResultadoInstrucaoCore> respostas;
        final List<IntencaoEfetivacao> chamadas = new ArrayList<>();

        FakeInstrucaoEfetivacaoCorePort(ResultadoInstrucaoCore... respostas) {
            this.respostas = new ArrayDeque<>(List.of(respostas));
        }

        @Override
        public ResultadoInstrucaoCore entregar(IntencaoEfetivacao intencao) {
            chamadas.add(intencao);
            return respostas.isEmpty() ? new ResultadoInstrucaoCore.FalhaTransitoria("sem mais respostas configuradas") : respostas.poll();
        }
    }

    /**
     * Modela uma UNICA linha de {@code outbox_entrega} em memoria, com a mesma regra de fencing
     * (claimId + status PENDENTE) do adapter real. {@code forcarDescarteNaProximaEscrita} simula
     * uma segunda reclamacao concorrente ter invalidado o claim entre a reclamacao desta chamada e
     * a escrita do resultado -- o cenario adversarial de verdade (duas transacoes reais) e provado
     * em S3.
     *
     * <p>Implementa TAMBEM {@link ResultadoEfetivacaoPort} sobre a mesma linha: em producao as
     * duas portas escrevem no mesmo banco, e a composicao de {@code executarSobClaim} atravessa as
     * duas -- um unico fake espelha isso sem sincronizacao artificial.
     */
    private static final class FakeEntregasEfetivacaoPort implements EntregasEfetivacaoPort, ResultadoEfetivacaoPort {
        private final IntencaoEfetivacao intencao;
        private final SolicitacaoId solicitacaoId = new SolicitacaoId(UUID.randomUUID());
        String status;
        int tentativas;
        UUID claimAtual;
        Instant proximaTentativaEm;
        boolean forcarDescarteNaProximaEscrita = false;
        ProtocoloCore protocoloRegistrado;
        MotivoFalhaEfetivacao motivoFalhaConcluido;

        private FakeEntregasEfetivacaoPort(IntencaoEfetivacao intencao, boolean semPendente) {
            this.intencao = intencao;
            this.status = semPendente ? null : "PENDENTE";
            this.proximaTentativaEm = Instant.EPOCH;
        }

        static FakeEntregasEfetivacaoPort semPendente() {
            return new FakeEntregasEfetivacaoPort(null, true);
        }

        static FakeEntregasEfetivacaoPort comEntregaPendente(IntencaoEfetivacao intencao) {
            return new FakeEntregasEfetivacaoPort(intencao, false);
        }

        @Override
        public ReclamacaoEntrega reclamarProxima(Instant agora, int maxTentativas, Duration lease) {
            if (status == null || !"PENDENTE".equals(status) || proximaTentativaEm.isAfter(agora)) {
                return new ReclamacaoEntrega.NenhumaPendente();
            }
            if (tentativas >= maxTentativas) {
                status = "ESGOTADA";
                claimAtual = null;
                proximaTentativaEm = null;
                return new ReclamacaoEntrega.EsgotadaAgora();
            }
            claimAtual = UUID.randomUUID();
            tentativas++;
            // claimDevolvido e o que o caso de uso recebe e devolvera na escrita; quando o teste
            // forca descarte, claimAtual e trocado LOGO DEPOIS (simulando outro worker reclamando
            // antes desta chamada terminar seu proprio episodio), entao a escrita vai comparar um
            // claim ja obsoleto.
            UUID claimDevolvido = claimAtual;
            if (forcarDescarteNaProximaEscrita) {
                claimAtual = UUID.randomUUID();
            }
            return new ReclamacaoEntrega.Reclamada(new EntregaEfetivacaoReclamada(claimDevolvido, intencao, solicitacaoId, tentativas));
        }

        @Override
        public ResultadoRegistroEntrega registrarAceite(EntregaEfetivacaoReclamada claim, ProtocoloCore protocoloCore, Instant agora) {
            if (!fencingValido(claim)) {
                return ResultadoRegistroEntrega.DESCARTADO_CLAIM_OBSOLETO;
            }
            status = "ACEITA";
            protocoloRegistrado = protocoloCore;
            claimAtual = null;
            return ResultadoRegistroEntrega.APLICADO;
        }

        @Override
        public ResultadoRegistroEntrega reagendar(EntregaEfetivacaoReclamada claim, Instant proximaTentativaEm, String erroSanitizado, Instant agora) {
            if (!fencingValido(claim)) {
                return ResultadoRegistroEntrega.DESCARTADO_CLAIM_OBSOLETO;
            }
            this.proximaTentativaEm = proximaTentativaEm;
            claimAtual = null;
            return ResultadoRegistroEntrega.APLICADO;
        }

        @Override
        public ResultadoRegistroEntrega marcarIndeterminada(EntregaEfetivacaoReclamada claim, String erroSanitizado, Instant agora) {
            if (!fencingValido(claim)) {
                return ResultadoRegistroEntrega.DESCARTADO_CLAIM_OBSOLETO;
            }
            status = "INDETERMINADA";
            claimAtual = null;
            return ResultadoRegistroEntrega.APLICADO;
        }

        @Override
        public boolean claimAindaValido(EntregaEfetivacaoReclamada claim) {
            return fencingValido(claim);
        }

        @Override
        public void terminalizarPorFalhaDefinitiva(EntregaEfetivacaoReclamada claim, Instant agora) {
            status = "FALHA_DEFINITIVA";
            claimAtual = null;
        }

        /** Nao usado neste teste: nenhum cenario aqui modela conclusao concorrente (ver {@code RegistrarResultadoEfetivacaoTest}). */
        @Override
        public void terminalizarPorConclusaoConcorrente(
                EntregaEfetivacaoReclamada claim, StatusSolicitacaoAumentoLimite terminalObservado, Instant agora) {
            throw new UnsupportedOperationException("nao usado neste teste");
        }

        @Override
        public ResultadoRegistroEfetivacao registrar(
                EfetivacaoId efetivacaoId, ResultadoEfetivacaoRecebido resultado, Optional<ProtocoloCore> protocoloInformado,
                AtorOperacao autor, Instant agora) {
            if (resultado instanceof ResultadoEfetivacaoRecebido.FalhaDefinitiva falhaDefinitiva) {
                motivoFalhaConcluido = falhaDefinitiva.motivo();
            }
            return new ResultadoRegistroEfetivacao.Concluida(StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO, Duration.ofMinutes(5));
        }

        private boolean fencingValido(EntregaEfetivacaoReclamada claim) {
            return "PENDENTE".equals(status) && claim.claimId().equals(claimAtual);
        }
    }
}
