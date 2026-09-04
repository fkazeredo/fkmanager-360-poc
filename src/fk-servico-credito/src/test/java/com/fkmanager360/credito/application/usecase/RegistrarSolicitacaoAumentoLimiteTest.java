package com.fkmanager360.credito.application.usecase;

import com.fkmanager360.credito.application.ComandoSolicitacaoAumentoLimite;
import com.fkmanager360.credito.application.port.out.CargaParaDecisao;
import com.fkmanager360.credito.application.port.out.ComandoInvalidoException;
import com.fkmanager360.credito.application.port.out.ContaNaoEncontradaException;
import com.fkmanager360.credito.application.port.out.DadosCreditoCorePort;
import com.fkmanager360.credito.application.port.out.DireitoDeAtendimentoAusenteException;
import com.fkmanager360.credito.application.port.out.DireitoDeAtendimentoPort;
import com.fkmanager360.credito.application.port.out.EntradaHistorico;
import com.fkmanager360.credito.application.port.out.IdempotenciaFingerprintDivergenteException;
import com.fkmanager360.credito.application.port.out.IntencaoEfetivacao;
import com.fkmanager360.credito.application.port.out.LimiteSolicitadoNaoAumentaException;
import com.fkmanager360.credito.application.port.out.LimiteVigenteDesatualizadoException;
import com.fkmanager360.credito.application.port.out.NovaSolicitacaoAumentoLimite;
import com.fkmanager360.credito.application.port.out.RegistroIdempotencia;
import com.fkmanager360.credito.application.port.out.RegistroIdempotenciaPort;
import com.fkmanager360.credito.application.port.out.RegistroIdempotenteEncontrado;
import com.fkmanager360.credito.application.port.out.ResultadoAplicacaoDecisao;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroSolicitacao;
import com.fkmanager360.credito.application.port.out.SolicitacaoCriada;
import com.fkmanager360.credito.application.port.out.SolicitacaoNaoTerminalExistente;
import com.fkmanager360.credito.application.port.out.SolicitacaoNaoTerminalExistenteException;
import com.fkmanager360.credito.application.port.out.SolicitacoesAumentoLimitePort;
import com.fkmanager360.credito.domain.AtorId;
import com.fkmanager360.credito.domain.ClassificacaoRiscoCreditoBase;
import com.fkmanager360.credito.domain.ClienteId;
import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.ContextoDecisaoCredito;
import com.fkmanager360.credito.domain.CorrelationId;
import com.fkmanager360.credito.domain.DadosCreditoCore;
import com.fkmanager360.credito.domain.DecisaoCredito;
import com.fkmanager360.credito.domain.IdempotencyKey;
import com.fkmanager360.credito.domain.LimiteChequeEspecialVigente;
import com.fkmanager360.credito.domain.MotorDecisaoCredito;
import com.fkmanager360.credito.domain.PoliticaCreditoV1;
import com.fkmanager360.credito.domain.ResultadoDecisaoCredito;
import com.fkmanager360.credito.domain.SituacaoConta;
import com.fkmanager360.credito.domain.SolicitacaoId;
import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;
import com.fkmanager360.credito.domain.VersaoPoliticaCredito;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S2: orquestracao da Fase 0 + Fase 1 (plano #0003), sem Spring, com fakes comportamentais
 * pequenos que contam invocacoes -- estilo de {@code ConsultarLimiteChequeEspecialVigenteTest}
 * (ADR-0018).
 */
class RegistrarSolicitacaoAumentoLimiteTest {

    private static final ClienteId CLIENTE = new ClienteId("1");
    private static final ContaId CONTA = new ContaId("10001");
    private static final AtorId ORIGINADOR = new AtorId("gerente-1");
    private static final Instant AGORA = Instant.parse("2026-09-02T16:00:00Z");
    private static final VersaoPoliticaCredito V1 = PoliticaCreditoV1.VERSAO;

    private static ComandoSolicitacaoAumentoLimite comando(
            Long limiteSolicitado, Long limiteVigenteVisto, String canal, String observacao) {
        return new ComandoSolicitacaoAumentoLimite(
                CLIENTE, CONTA, limiteSolicitado, limiteVigenteVisto, canal, observacao,
                ORIGINADOR, new IdempotencyKey(UUID.randomUUID()));
    }

    private static ComandoSolicitacaoAumentoLimite comandoValido(long limiteSolicitado, long limiteVigenteVisto) {
        return comando(limiteSolicitado, limiteVigenteVisto, "PRESENCIAL", "manifestacao valida");
    }

    private static DadosCreditoCore dadosAprovaveis(long vigenteCentavos) {
        return new DadosCreditoCore(
                new LimiteChequeEspecialVigente(vigenteCentavos), SituacaoConta.REGULAR,
                ClassificacaoRiscoCreditoBase.BAIXO, AGORA, "CoreLegado");
    }

    private static MotorDecisaoCredito motorPadrao() {
        return new MotorDecisaoCredito(List.of(new PoliticaCreditoV1()), V1);
    }

    private static RegistrarSolicitacaoAumentoLimite useCase(
            DireitoDeAtendimentoPort direito, DadosCreditoCorePort core,
            RegistroIdempotenciaPort registroIdempotencia, SolicitacoesAumentoLimitePortFake solicitacoes) {
        var motor = motorPadrao();
        var decidir = new DecidirSolicitacaoAumentoLimite(solicitacoes, motor);
        return new RegistrarSolicitacaoAumentoLimite(direito, core, registroIdempotencia, solicitacoes, motor, decidir);
    }

    // --- Ordem por ausencia de efeito ---------------------------------------------------------

    @Test
    void semDireitoDeAtendimento_coreEChamadoZeroVezes() {
        var core = new DadosCreditoCorePortFake(Optional.of(dadosAprovaveis(500_000)));
        var direito = new DireitoDeAtendimentoPortFake(new DireitoDeAtendimentoAusenteException("sem direito"));
        var registroIdempotencia = new RegistroIdempotenciaPortFake(Optional.empty());
        var solicitacoes = new SolicitacoesAumentoLimitePortFake();

        var useCase = useCase(direito, core, registroIdempotencia, solicitacoes);

        assertThatThrownBy(() -> useCase.executar(comandoValido(600_000, 500_000), AGORA))
                .isInstanceOf(DireitoDeAtendimentoAusenteException.class);

        assertThat(core.chamadas).isZero();
        assertThat(solicitacoes.chamadasRegistrar).isZero();
    }

    @Test
    void limiteSolicitadoNaoPositivo_eLimiteVigenteVistoNegativo_ambosOsFakesRemotosComZeroChamadas() {
        var core = new DadosCreditoCorePortFake(Optional.of(dadosAprovaveis(500_000)));
        var direito = new DireitoDeAtendimentoPortFake(null);
        var registroIdempotencia = new RegistroIdempotenciaPortFake(Optional.empty());
        var solicitacoes = new SolicitacoesAumentoLimitePortFake();
        var useCase = useCase(direito, core, registroIdempotencia, solicitacoes);

        assertThatThrownBy(() -> useCase.executar(comando(-1L, -1L, "PRESENCIAL", null), AGORA))
                .isInstanceOf(ComandoInvalidoException.class);

        assertThat(direito.chamadas).isZero();
        assertThat(core.chamadas).isZero();
    }

    @Test
    void limiteSolicitadoAusente_eComandoInvalido_semTocarPortasRemotas() {
        var core = new DadosCreditoCorePortFake(Optional.of(dadosAprovaveis(500_000)));
        var direito = new DireitoDeAtendimentoPortFake(null);
        var registroIdempotencia = new RegistroIdempotenciaPortFake(Optional.empty());
        var solicitacoes = new SolicitacoesAumentoLimitePortFake();
        var useCase = useCase(direito, core, registroIdempotencia, solicitacoes);

        assertThatThrownBy(() -> useCase.executar(comando(null, 500_000L, "PRESENCIAL", null), AGORA))
                .isInstanceOf(ComandoInvalidoException.class);

        assertThat(direito.chamadas).isZero();
        assertThat(core.chamadas).isZero();
    }

    @Test
    void canalForaDoEnum_eComandoInvalido_semTocarPortasRemotas() {
        var core = new DadosCreditoCorePortFake(Optional.of(dadosAprovaveis(500_000)));
        var direito = new DireitoDeAtendimentoPortFake(null);
        var registroIdempotencia = new RegistroIdempotenciaPortFake(Optional.empty());
        var solicitacoes = new SolicitacoesAumentoLimitePortFake();
        var useCase = useCase(direito, core, registroIdempotencia, solicitacoes);

        assertThatThrownBy(() -> useCase.executar(comando(600_000L, 500_000L, "PIGEON_POST", null), AGORA))
                .isInstanceOf(ComandoInvalidoException.class);

        assertThat(direito.chamadas).isZero();
        assertThat(core.chamadas).isZero();
    }

    @Test
    void observacaoAcimaDe500Caracteres_eComandoInvalido() {
        var core = new DadosCreditoCorePortFake(Optional.of(dadosAprovaveis(500_000)));
        var direito = new DireitoDeAtendimentoPortFake(null);
        var registroIdempotencia = new RegistroIdempotenciaPortFake(Optional.empty());
        var solicitacoes = new SolicitacoesAumentoLimitePortFake();
        var useCase = useCase(direito, core, registroIdempotencia, solicitacoes);

        assertThatThrownBy(() -> useCase.executar(
                comando(600_000L, 500_000L, "PRESENCIAL", "a".repeat(501)), AGORA))
                .isInstanceOf(ComandoInvalidoException.class);
    }

    // --- Caso decisivo: stale check precede a comparacao com o vigente ------------------------

    @Test
    void casoDecisivo_visto5000_core6000_pedido5500_devolveLimiteVigenteDesatualizado_nuncaNaoAumenta() {
        var core = new DadosCreditoCorePortFake(Optional.of(dadosAprovaveis(600_000)));
        var direito = new DireitoDeAtendimentoPortFake(null);
        var registroIdempotencia = new RegistroIdempotenciaPortFake(Optional.empty());
        var solicitacoes = new SolicitacoesAumentoLimitePortFake();
        var useCase = useCase(direito, core, registroIdempotencia, solicitacoes);

        assertThatThrownBy(() -> useCase.executar(comandoValido(550_000, 500_000), AGORA))
                .isInstanceOf(LimiteVigenteDesatualizadoException.class)
                .isNotInstanceOf(LimiteSolicitadoNaoAumentaException.class);

        assertThat(solicitacoes.chamadasRegistrar).isZero();
    }

    @Test
    void limiteVigenteVistoCoerente_masLimiteSolicitadoNaoMaior_devolveLimiteSolicitadoNaoAumenta() {
        var core = new DadosCreditoCorePortFake(Optional.of(dadosAprovaveis(500_000)));
        var direito = new DireitoDeAtendimentoPortFake(null);
        var registroIdempotencia = new RegistroIdempotenciaPortFake(Optional.empty());
        var solicitacoes = new SolicitacoesAumentoLimitePortFake();
        var useCase = useCase(direito, core, registroIdempotencia, solicitacoes);

        assertThatThrownBy(() -> useCase.executar(comandoValido(500_000, 500_000), AGORA))
                .isInstanceOf(LimiteSolicitadoNaoAumentaException.class);

        assertThat(solicitacoes.chamadasRegistrar).isZero();
    }

    @Test
    void contaDesconhecidaPeloCore_lancaContaNaoEncontrada() {
        var core = new DadosCreditoCorePortFake(Optional.empty());
        var direito = new DireitoDeAtendimentoPortFake(null);
        var registroIdempotencia = new RegistroIdempotenciaPortFake(Optional.empty());
        var solicitacoes = new SolicitacoesAumentoLimitePortFake();
        var useCase = useCase(direito, core, registroIdempotencia, solicitacoes);

        assertThatThrownBy(() -> useCase.executar(comandoValido(600_000, 500_000), AGORA))
                .isInstanceOf(ContaNaoEncontradaException.class);
    }

    // --- Idempotencia: pre-check -----------------------------------------------------------

    @Test
    void preCheck_mesmaKeyMesmoFingerprint_solicitacaoJaDecidida_replaySemTocarPortasRemotas() {
        var solicitacoes = new SolicitacoesAumentoLimitePortFake();
        var contexto = contextoAprovavel();
        SolicitacaoId id = solicitacoes.semear(StatusSolicitacaoAumentoLimite.REJEITADA, contexto, CONTA,
                new CorrelationId(UUID.randomUUID()));
        // Semeia tambem a decisao ja tomada, para que o replay a encontre pronta.
        solicitacoes.decisaoPreExistente(id, decisaoRejeitadaDeExemplo());

        var comando = comando(600_000L, 500_000L, "PRESENCIAL", null);
        String fingerprint = com.fkmanager360.credito.application.FingerprintCanonico.calcular(
                comando.clienteId(), comando.contaId(), comando.limiteSolicitado(), comando.limiteVigenteVisto(),
                comando.canalManifestacao(), comando.observacao());
        var registro = new RegistroIdempotencia(ORIGINADOR, comando.idempotencyKey(), fingerprint, id, AGORA);

        var core = new DadosCreditoCorePortFake(Optional.of(dadosAprovaveis(500_000)));
        var direito = new DireitoDeAtendimentoPortFake(null);
        var registroIdempotencia = new RegistroIdempotenciaPortFake(Optional.of(registro));
        var useCase = useCase(direito, core, registroIdempotencia, solicitacoes);

        var resultado = useCase.executar(comando, AGORA);

        assertThat(resultado.criacaoNova()).isFalse();
        assertThat(resultado.solicitacaoId()).isEqualTo(id);
        assertThat(direito.chamadas).isZero();
        assertThat(core.chamadas).isZero();
        assertThat(solicitacoes.chamadasRegistrar).isZero();
    }

    @Test
    void preCheck_mesmaKeyFingerprintDivergente_lancaExceptionSemTocarNadaRemoto() {
        var solicitacoes = new SolicitacoesAumentoLimitePortFake();
        SolicitacaoId idOriginal = new SolicitacaoId(UUID.randomUUID());
        var registro = new RegistroIdempotencia(
                ORIGINADOR, new IdempotencyKey(UUID.randomUUID()), "fingerprint-gravado-diferente", idOriginal, AGORA);

        var core = new DadosCreditoCorePortFake(Optional.of(dadosAprovaveis(500_000)));
        var direito = new DireitoDeAtendimentoPortFake(null);
        var registroIdempotencia = new RegistroIdempotenciaPortFake(Optional.of(registro));
        var useCase = useCase(direito, core, registroIdempotencia, solicitacoes);

        assertThatThrownBy(() -> useCase.executar(comandoValido(600_000, 500_000), AGORA))
                .isInstanceOf(IdempotenciaFingerprintDivergenteException.class);

        assertThat(direito.chamadas).isZero();
        assertThat(core.chamadas).isZero();
        assertThat(solicitacoes.chamadasRegistrar).isZero();
    }

    // --- Corrida simulada: mesma classificacao nao importa de qual caminho o registro veio ----

    @Test
    void conflitoDeTx1_registrarDevolveRegistroIdempotenteEncontrado_classificaIgualAoPreCheck() {
        var solicitacoes = new SolicitacoesAumentoLimitePortFake();
        var contexto = contextoAprovavel();
        SolicitacaoId id = solicitacoes.semear(StatusSolicitacaoAumentoLimite.SOLICITADA, contexto, CONTA,
                new CorrelationId(UUID.randomUUID()));

        var comando = comandoValido(600_000, 500_000);
        String fingerprint = com.fkmanager360.credito.application.FingerprintCanonico.calcular(
                comando.clienteId(), comando.contaId(), comando.limiteSolicitado(), comando.limiteVigenteVisto(),
                comando.canalManifestacao(), comando.observacao());
        var registro = new RegistroIdempotencia(ORIGINADOR, comando.idempotencyKey(), fingerprint, id, AGORA);

        // Pre-check nao encontra nada (Optional.empty()), mas o "registrar" simula um conflito de
        // TX1 ja resolvido pelo adapter, devolvendo RegistroIdempotenteEncontrado mesmo na
        // primeira tentativa.
        solicitacoes.forcarProximoResultadoRegistrar(new RegistroIdempotenteEncontrado(registro));

        var core = new DadosCreditoCorePortFake(Optional.of(dadosAprovaveis(500_000)));
        var direito = new DireitoDeAtendimentoPortFake(null);
        var registroIdempotencia = new RegistroIdempotenciaPortFake(Optional.empty());
        var useCase = useCase(direito, core, registroIdempotencia, solicitacoes);

        var resultado = useCase.executar(comando, AGORA);

        // A solicitacao referenciada pelo conflito estava SOLICITADA -> a retomada decide agora e
        // aprova (contexto favoravel), exatamente como se o registro tivesse sido achado no
        // pre-check. criacaoNova continua false: o "registrar" desta chamada nao criou nada.
        assertThat(resultado.criacaoNova()).isFalse();
        assertThat(resultado.status()).isEqualTo(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
    }

    @Test
    void conflitoDeTx1_semRegistroDeIdempotencia_lancaSolicitacaoNaoTerminalExistente() {
        var solicitacoes = new SolicitacoesAumentoLimitePortFake();
        solicitacoes.forcarProximoResultadoRegistrar(new SolicitacaoNaoTerminalExistente());

        var core = new DadosCreditoCorePortFake(Optional.of(dadosAprovaveis(500_000)));
        var direito = new DireitoDeAtendimentoPortFake(null);
        var registroIdempotencia = new RegistroIdempotenciaPortFake(Optional.empty());
        var useCase = useCase(direito, core, registroIdempotencia, solicitacoes);

        assertThatThrownBy(() -> useCase.executar(comandoValido(600_000, 500_000), AGORA))
                .isInstanceOf(SolicitacaoNaoTerminalExistenteException.class);
    }

    // --- Ordenacao estrita: todo I/O remoto antes de qualquer persistencia ---------------------

    @Test
    void ordemDeExecucao_direitoDeAtendimento_depoisCore_depoisPersistencia() {
        var ordem = new ArrayList<String>();
        var core = new DadosCreditoCorePortFake(Optional.of(dadosAprovaveis(500_000)), ordem);
        var direito = new DireitoDeAtendimentoPortFake(null, ordem);
        var registroIdempotencia = new RegistroIdempotenciaPortFake(Optional.empty());
        var solicitacoes = new SolicitacoesAumentoLimitePortFake(ordem);
        var useCase = useCase(direito, core, registroIdempotencia, solicitacoes);

        useCase.executar(comandoValido(600_000, 500_000), AGORA);

        assertThat(ordem).containsExactly(
                "direitoDeAtendimento", "dadosCreditoCore", "registrar", "carregarParaDecisao", "aplicarDecisao");
    }

    // --- Fluxo completo: aprovacao e rejeicao na mesma resposta --------------------------------

    @Test
    void submissaoAprovavel_criaSolicitacaoEDecideNaMesmaChamada() {
        var core = new DadosCreditoCorePortFake(Optional.of(dadosAprovaveis(500_000)));
        var direito = new DireitoDeAtendimentoPortFake(null);
        var registroIdempotencia = new RegistroIdempotenciaPortFake(Optional.empty());
        var solicitacoes = new SolicitacoesAumentoLimitePortFake();
        var useCase = useCase(direito, core, registroIdempotencia, solicitacoes);

        var resultado = useCase.executar(comandoValido(600_000, 500_000), AGORA);

        assertThat(resultado.criacaoNova()).isTrue();
        assertThat(resultado.status()).isEqualTo(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        assertThat(resultado.decisao().resultado()).isEqualTo(ResultadoDecisaoCredito.APROVADA);
        assertThat(resultado.decisao().versaoPoliticaCredito()).isEqualTo(V1);
    }

    @Test
    void submissaoForaDaPolitica_criaSolicitacaoRejeitadaNaMesmaChamada() {
        var core = new DadosCreditoCorePortFake(Optional.of(dadosAprovaveis(100_000)));
        var direito = new DireitoDeAtendimentoPortFake(null);
        var registroIdempotencia = new RegistroIdempotenciaPortFake(Optional.empty());
        var solicitacoes = new SolicitacoesAumentoLimitePortFake();
        var useCase = useCase(direito, core, registroIdempotencia, solicitacoes);

        var resultado = useCase.executar(comandoValido(5_000_000, 100_000), AGORA);

        assertThat(resultado.criacaoNova()).isTrue();
        assertThat(resultado.status()).isEqualTo(StatusSolicitacaoAumentoLimite.REJEITADA);
        assertThat(resultado.decisao().resultado()).isEqualTo(ResultadoDecisaoCredito.REJEITADA);
    }

    // --- Auxiliares ---------------------------------------------------------------------------

    private static ContextoDecisaoCredito contextoAprovavel() {
        return ContextoDecisaoCredito.congelar(
                dadosAprovaveis(500_000), new com.fkmanager360.credito.domain.LimiteSolicitado(600_000), V1, AGORA);
    }

    private static DecisaoCredito decisaoRejeitadaDeExemplo() {
        return new DecisaoCredito(
                ResultadoDecisaoCredito.REJEITADA, com.fkmanager360.credito.domain.MotivoDecisaoCredito.CONTA_NAO_ELEGIVEL,
                V1, AGORA, com.fkmanager360.credito.domain.AtorSistema.MOTOR_DECISAO_CREDITO);
    }

    // --- Fakes comportamentais ------------------------------------------------------------

    private static final class DireitoDeAtendimentoPortFake implements DireitoDeAtendimentoPort {
        private final RuntimeException falhaOuNull;
        private final List<String> ordem;
        int chamadas;

        DireitoDeAtendimentoPortFake(RuntimeException falhaOuNull) {
            this(falhaOuNull, null);
        }

        DireitoDeAtendimentoPortFake(RuntimeException falhaOuNull, List<String> ordem) {
            this.falhaOuNull = falhaOuNull;
            this.ordem = ordem;
        }

        @Override
        public void confirmarDireitoDeAtendimento(ClienteId clienteId, ContaId contaId) {
            chamadas++;
            if (ordem != null) {
                ordem.add("direitoDeAtendimento");
            }
            if (falhaOuNull != null) {
                throw falhaOuNull;
            }
        }
    }

    private static final class DadosCreditoCorePortFake implements DadosCreditoCorePort {
        private final Optional<DadosCreditoCore> resposta;
        private final List<String> ordem;
        int chamadas;

        DadosCreditoCorePortFake(Optional<DadosCreditoCore> resposta) {
            this(resposta, null);
        }

        DadosCreditoCorePortFake(Optional<DadosCreditoCore> resposta, List<String> ordem) {
            this.resposta = resposta;
            this.ordem = ordem;
        }

        @Override
        public Optional<DadosCreditoCore> consultar(ContaId contaId) {
            chamadas++;
            if (ordem != null) {
                ordem.add("dadosCreditoCore");
            }
            return resposta;
        }
    }

    private static final class RegistroIdempotenciaPortFake implements RegistroIdempotenciaPort {
        private final Optional<RegistroIdempotencia> resposta;
        int chamadas;

        RegistroIdempotenciaPortFake(Optional<RegistroIdempotencia> resposta) {
            this.resposta = resposta;
        }

        @Override
        public Optional<RegistroIdempotencia> buscar(AtorId originadorId, IdempotencyKey key) {
            chamadas++;
            return resposta;
        }
    }

    /**
     * Fake em memoria de SolicitacoesAumentoLimitePort. Comportamento padrao: {@code registrar}
     * cria uma nova solicitacao SOLICITADA; {@code aplicarDecisao} so escreve quando o status
     * atual e SOLICITADA. {@code forcarProximoResultadoRegistrar} permite simular um conflito de
     * TX1 ja resolvido pelo adapter, sem depender de infraestrutura real (essa e S3, proxima
     * etapa).
     */
    private static final class SolicitacoesAumentoLimitePortFake implements SolicitacoesAumentoLimitePort {
        private final Map<SolicitacaoId, StatusSolicitacaoAumentoLimite> status = new LinkedHashMap<>();
        private final Map<SolicitacaoId, ContextoDecisaoCredito> contextos = new LinkedHashMap<>();
        private final Map<SolicitacaoId, ContaId> contas = new LinkedHashMap<>();
        private final Map<SolicitacaoId, CorrelationId> correlationIds = new LinkedHashMap<>();
        private final Map<SolicitacaoId, DecisaoCredito> decisoes = new LinkedHashMap<>();
        private final Set<String> fatoIdsGravados = new LinkedHashSet<>();
        private final List<String> ordem;

        private ResultadoRegistroSolicitacao resultadoRegistrarForcado;

        int chamadasRegistrar;
        int chamadasAplicarDecisaoComEscrita;
        int chamadasAplicarDecisaoSemEscrita;

        SolicitacoesAumentoLimitePortFake() {
            this(null);
        }

        SolicitacoesAumentoLimitePortFake(List<String> ordem) {
            this.ordem = ordem;
        }

        SolicitacaoId semear(StatusSolicitacaoAumentoLimite statusInicial, ContextoDecisaoCredito contexto,
                              ContaId contaId, CorrelationId correlationId) {
            SolicitacaoId id = new SolicitacaoId(UUID.randomUUID());
            status.put(id, statusInicial);
            contextos.put(id, contexto);
            contas.put(id, contaId);
            correlationIds.put(id, correlationId);
            return id;
        }

        void decisaoPreExistente(SolicitacaoId id, DecisaoCredito decisao) {
            decisoes.put(id, decisao);
        }

        void forcarProximoResultadoRegistrar(ResultadoRegistroSolicitacao resultado) {
            this.resultadoRegistrarForcado = resultado;
        }

        @Override
        public ResultadoRegistroSolicitacao registrar(NovaSolicitacaoAumentoLimite dados) {
            chamadasRegistrar++;
            if (ordem != null) {
                ordem.add("registrar");
            }
            if (resultadoRegistrarForcado != null) {
                return resultadoRegistrarForcado;
            }
            SolicitacaoId id = new SolicitacaoId(UUID.randomUUID());
            status.put(id, StatusSolicitacaoAumentoLimite.SOLICITADA);
            contextos.put(id, dados.contextoDecisaoCredito());
            contas.put(id, dados.contaId());
            correlationIds.put(id, dados.correlationId());
            return new SolicitacaoCriada(id);
        }

        @Override
        public CargaParaDecisao carregarParaDecisao(SolicitacaoId id) {
            if (ordem != null) {
                ordem.add("carregarParaDecisao");
            }
            return new CargaParaDecisao(status.get(id), contextos.get(id), contas.get(id), correlationIds.get(id),
                    Instant.parse("2026-09-03T10:00:00Z"));
        }

        @Override
        public ResultadoAplicacaoDecisao aplicarDecisao(
                SolicitacaoId id, DecisaoCredito decisao, IntencaoEfetivacao intencaoOuNull, EntradaHistorico entrada) {
            if (ordem != null) {
                ordem.add("aplicarDecisao");
            }
            StatusSolicitacaoAumentoLimite atual = status.get(id);
            if (atual == StatusSolicitacaoAumentoLimite.SOLICITADA) {
                chamadasAplicarDecisaoComEscrita++;
                StatusSolicitacaoAumentoLimite novoStatus = decisao.resultado() == ResultadoDecisaoCredito.APROVADA
                        ? StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO
                        : StatusSolicitacaoAumentoLimite.REJEITADA;
                status.put(id, novoStatus);
                decisoes.put(id, decisao);
                fatoIdsGravados.add(entrada.fatoId());
                return new ResultadoAplicacaoDecisao(true, novoStatus, decisao);
            }
            chamadasAplicarDecisaoSemEscrita++;
            return new ResultadoAplicacaoDecisao(false, atual, decisoes.get(id));
        }
    }
}
