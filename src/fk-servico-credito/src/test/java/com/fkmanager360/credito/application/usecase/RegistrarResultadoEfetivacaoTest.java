package com.fkmanager360.credito.application.usecase;

import com.fkmanager360.credito.application.port.out.EntregaEfetivacaoReclamada;
import com.fkmanager360.credito.application.port.out.EntregasEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.IntencaoEfetivacao;
import com.fkmanager360.credito.application.port.out.ReclamacaoEntrega;
import com.fkmanager360.credito.application.port.out.ResultadoConclusaoDefinitiva;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoRecebido;
import com.fkmanager360.credito.application.port.out.ResultadoIndeterminacao;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroEfetivacao;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroEntrega;
import com.fkmanager360.credito.application.port.out.TransacaoPort;
import com.fkmanager360.credito.domain.AtorOperacao;
import com.fkmanager360.credito.domain.AtorSistema;
import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.CorrelationId;
import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.LimiteChequeEspecialVigente;
import com.fkmanager360.credito.domain.LimiteSolicitado;
import com.fkmanager360.credito.domain.MotivoFalhaEfetivacao;
import com.fkmanager360.credito.domain.ProtocoloCore;
import com.fkmanager360.credito.domain.SolicitacaoAumentoLimite;
import com.fkmanager360.credito.domain.SolicitacaoId;
import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2 (ADR-0018): {@code RegistrarResultadoEfetivacao} e o unico caso de uso de conclusao (ticket
 * #0004, Objetivo) -- este teste prova a orquestracao com um fake comportamental que reproduz a
 * MESMA classificacao terminal em tres eixos (protocolo, resultado/motivo, limite efetivado) que
 * {@code JpaResultadoEfetivacaoAdapter} implementa contra PostgreSQL (S3), sobre o MESMO
 * {@link SolicitacaoAumentoLimite} de dominio (a maquina de estados ja exaustiva em S1 desde
 * #0003) -- nenhuma segunda tabela de transicoes. A entrada {@code executarSobClaim} e provada
 * aqui na composicao (fencing -&gt; conclusao -&gt; terminalizacao); a atomicidade real contra
 * PostgreSQL, incluindo a unidade transacional unica e a ordem de locks, e provada em S3.
 */
class RegistrarResultadoEfetivacaoTest {

    private static final EfetivacaoId EFETIVACAO_ID = new EfetivacaoId(UUID.randomUUID());
    private static final AtorOperacao AUTOR = AtorSistema.CORE_LEGADO;
    private static final Instant AGORA = Instant.parse("2026-09-03T12:00:00Z");
    private static final long LIMITE_SOLICITADO_CONGELADO = 600_000L;

    /** {@code TransacaoPort} de teste: executa a unidade diretamente (S2 nao prova atomicidade). */
    private static final TransacaoPort TRANSACAO_PASSA_DIRETO = new TransacaoPort() {
        @Override
        public <T> T executar(Supplier<T> unidade) {
            return unidade.get();
        }
    };

    private static RegistrarResultadoEfetivacao usecase(FakeResultadoEfetivacaoPort resultado, FakeEntregaComClaim entrega) {
        return new RegistrarResultadoEfetivacao(resultado, entrega, TRANSACAO_PASSA_DIRETO);
    }

    private static EntregaEfetivacaoReclamada claimDeTeste() {
        return new EntregaEfetivacaoReclamada(
                UUID.randomUUID(),
                new IntencaoEfetivacao(
                        EFETIVACAO_ID, UUID.randomUUID(), new ContaId("10001"),
                        new LimiteChequeEspecialVigente(500_000), new LimiteSolicitado(LIMITE_SOLICITADO_CONGELADO),
                        new CorrelationId(UUID.randomUUID())),
                new SolicitacaoId(UUID.randomUUID()),
                1);
    }

    // --- executar: caminho sem claim (callback/#0005, reconciliacao/#0006) ---------------------

    @Test
    void executar_solicitacaoAguardandoEfetivacao_concluiComFalhaDefinitiva() {
        FakeResultadoEfetivacaoPort fake = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        RegistrarResultadoEfetivacao usecase = usecase(fake, new FakeEntregaComClaim(true));

        ResultadoRegistroEfetivacao resultado = usecase.executar(
                EFETIVACAO_ID, new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE),
                Optional.empty(), AUTOR, AGORA);

        assertThat(resultado).isInstanceOf(ResultadoRegistroEfetivacao.Concluida.class);
        ResultadoRegistroEfetivacao.Concluida concluida = (ResultadoRegistroEfetivacao.Concluida) resultado;
        assertThat(concluida.statusResultante()).isEqualTo(StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO);
        assertThat(concluida.permanenciaEmAguardandoEfetivacao()).isEqualTo(Duration.ofMinutes(5));
        assertThat(fake.chamadasDeRegistro).isEqualTo(1);
    }

    @Test
    void executar_sucessoComLimiteCoerente_concluiComEfetivada() {
        FakeResultadoEfetivacaoPort fake = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        RegistrarResultadoEfetivacao usecase = usecase(fake, new FakeEntregaComClaim(true));

        ResultadoRegistroEfetivacao resultado = usecase.executar(
                EFETIVACAO_ID, new ResultadoEfetivacaoRecebido.Sucesso(LIMITE_SOLICITADO_CONGELADO),
                Optional.of(new ProtocoloCore("PRT-1")), AUTOR, AGORA);

        assertThat(resultado).isInstanceOf(ResultadoRegistroEfetivacao.Concluida.class);
        assertThat(((ResultadoRegistroEfetivacao.Concluida) resultado).statusResultante())
                .isEqualTo(StatusSolicitacaoAumentoLimite.EFETIVADA);
        assertThat(fake.protocoloCore).isEqualTo("PRT-1");
    }

    @Test
    void executar_duasVezesParaAMesmaEfetivacao_segundaChamadaEDuplicadoIdentico() {
        FakeResultadoEfetivacaoPort fake = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        RegistrarResultadoEfetivacao usecase = usecase(fake, new FakeEntregaComClaim(true));
        ResultadoEfetivacaoRecebido resultadoRecebido =
                new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE);

        ResultadoRegistroEfetivacao primeira = usecase.executar(EFETIVACAO_ID, resultadoRecebido, Optional.empty(), AUTOR, AGORA);
        ResultadoRegistroEfetivacao segunda =
                usecase.executar(EFETIVACAO_ID, resultadoRecebido, Optional.empty(), AUTOR, AGORA.plusSeconds(1));

        assertThat(primeira).isInstanceOf(ResultadoRegistroEfetivacao.Concluida.class);
        assertThat(segunda).isInstanceOf(ResultadoRegistroEfetivacao.JaTerminalIdentica.class);
        assertThat(((ResultadoRegistroEfetivacao.JaTerminalIdentica) segunda).statusPersistido())
                .isEqualTo(StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO);
        assertThat(fake.chamadasDeRegistro).isEqualTo(2);
    }

    @Test
    void executar_callbackContraditorioSobreEfetivada_naoReescreveERegistraContradicao() {
        FakeResultadoEfetivacaoPort fake = FakeResultadoEfetivacaoPort.terminalEfetivada("PRT-1");

        ResultadoRegistroEfetivacao resultado = usecase(fake, new FakeEntregaComClaim(true)).executar(
                EFETIVACAO_ID, new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_INEXISTENTE),
                Optional.empty(), AUTOR, AGORA);

        assertThat(resultado).isInstanceOf(ResultadoRegistroEfetivacao.JaTerminalContraditoria.class);
        assertThat(((ResultadoRegistroEfetivacao.JaTerminalContraditoria) resultado).statusPersistido())
                .isEqualTo(StatusSolicitacaoAumentoLimite.EFETIVADA);
        assertThat(fake.protocoloCore).isEqualTo("PRT-1");
    }

    // --- AC26: sucesso incoerente ---------------------------------------------------------------

    @Test
    void executar_sucessoComLimiteIncoerente_naoTransicionaERetornaSucessoIncoerente() {
        FakeResultadoEfetivacaoPort fake = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);

        ResultadoRegistroEfetivacao resultado = usecase(fake, new FakeEntregaComClaim(true)).executar(
                EFETIVACAO_ID, new ResultadoEfetivacaoRecebido.Sucesso(LIMITE_SOLICITADO_CONGELADO + 1),
                Optional.of(new ProtocoloCore("PRT-1")), AUTOR, AGORA);

        assertThat(resultado).isInstanceOf(ResultadoRegistroEfetivacao.SucessoIncoerente.class);
        assertThat(fake.status).isEqualTo(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        assertThat(fake.protocoloCore).isNull();
    }

    // --- Protocolo divergente --------------------------------------------------------------------

    @Test
    void executar_protocoloDivergenteEmNaoTerminal_naoSobrescreveERetornaProtocoloDivergente() {
        FakeResultadoEfetivacaoPort fake = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        fake.protocoloCore = "PRT-ORIGINAL";

        ResultadoRegistroEfetivacao resultado = usecase(fake, new FakeEntregaComClaim(true)).executar(
                EFETIVACAO_ID, new ResultadoEfetivacaoRecebido.Sucesso(LIMITE_SOLICITADO_CONGELADO),
                Optional.of(new ProtocoloCore("PRT-DIVERGENTE")), AUTOR, AGORA);

        assertThat(resultado).isInstanceOf(ResultadoRegistroEfetivacao.ProtocoloDivergente.class);
        assertThat(fake.protocoloCore).isEqualTo("PRT-ORIGINAL");
        assertThat(fake.status).isEqualTo(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
    }

    @Test
    void executar_protocoloDivergenteSobreTerminalEfetivada_eContraditorioMesmoComValorCoerente() {
        // EFETIVADA com P1 + sucesso coerente em VALOR mas P2: contraditorio, nunca duplicado --
        // protocolo e o PRIMEIRO eixo, e ele sozinho decide quando diverge.
        FakeResultadoEfetivacaoPort fake = FakeResultadoEfetivacaoPort.terminalEfetivada("PRT-1");

        ResultadoRegistroEfetivacao resultado = usecase(fake, new FakeEntregaComClaim(true)).executar(
                EFETIVACAO_ID, new ResultadoEfetivacaoRecebido.Sucesso(LIMITE_SOLICITADO_CONGELADO),
                Optional.of(new ProtocoloCore("PRT-2")), AUTOR, AGORA);

        assertThat(resultado).isInstanceOf(ResultadoRegistroEfetivacao.JaTerminalContraditoria.class);
        assertThat(fake.protocoloCore).isEqualTo("PRT-1");
    }

    @Test
    void executar_protocoloDivergenteSobreTerminalFalhaEfetivacao_eContraditorioMesmoComMotivoCoerente() {
        // FALHA_EFETIVACAO com P1 + mesmo motivo mas P2: contraditorio pelo mesmo motivo do teste
        // acima -- estado semanticamente inalterado.
        FakeResultadoEfetivacaoPort fake = FakeResultadoEfetivacaoPort.terminalFalha(
                MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE, "PRT-1");

        ResultadoRegistroEfetivacao resultado = usecase(fake, new FakeEntregaComClaim(true)).executar(
                EFETIVACAO_ID, new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE),
                Optional.of(new ProtocoloCore("PRT-2")), AUTOR, AGORA);

        assertThat(resultado).isInstanceOf(ResultadoRegistroEfetivacao.JaTerminalContraditoria.class);
        assertThat(fake.protocoloCore).isEqualTo("PRT-1");
        assertThat(fake.motivoFalha).isEqualTo(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE);
    }

    // --- registrarIndeterminacao (#0006, AC16) --------------------------------------------------

    @Test
    void registrarIndeterminacao_aguardandoEfetivacao_transicionaEDevolveIndeterminadaAgora() {
        FakeResultadoEfetivacaoPort fake = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);

        ResultadoIndeterminacao resultado = usecase(fake, new FakeEntregaComClaim(true))
                .registrarIndeterminacao(EFETIVACAO_ID, AGORA);

        assertThat(resultado).isInstanceOf(ResultadoIndeterminacao.IndeterminadaAgora.class);
        assertThat(fake.status).isEqualTo(StatusSolicitacaoAumentoLimite.EFETIVACAO_INDETERMINADA);
    }

    @Test
    void registrarIndeterminacao_jaIndeterminada_naoRegistraDeNovo_devolveJaEstavaIndeterminada() {
        FakeResultadoEfetivacaoPort fake = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.EFETIVACAO_INDETERMINADA);
        RegistrarResultadoEfetivacao usecase = usecase(fake, new FakeEntregaComClaim(true));

        ResultadoIndeterminacao resultado = usecase.registrarIndeterminacao(EFETIVACAO_ID, AGORA);

        assertThat(resultado).isInstanceOf(ResultadoIndeterminacao.JaEstavaIndeterminada.class);
        assertThat(fake.status).isEqualTo(StatusSolicitacaoAumentoLimite.EFETIVACAO_INDETERMINADA);
    }

    @Test
    void registrarIndeterminacao_reentrada_eIdempotente_umaUnicaTransicao() {
        FakeResultadoEfetivacaoPort fake = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        RegistrarResultadoEfetivacao usecase = usecase(fake, new FakeEntregaComClaim(true));

        ResultadoIndeterminacao primeira = usecase.registrarIndeterminacao(EFETIVACAO_ID, AGORA);
        ResultadoIndeterminacao segunda = usecase.registrarIndeterminacao(EFETIVACAO_ID, AGORA.plusSeconds(1));

        assertThat(primeira).isInstanceOf(ResultadoIndeterminacao.IndeterminadaAgora.class);
        assertThat(segunda).isInstanceOf(ResultadoIndeterminacao.JaEstavaIndeterminada.class);
        assertThat(fake.chamadasDeIndeterminacao).isEqualTo(2);
    }

    /** Conclusao tardia em FALHA autoritativa sobre EFETIVACAO_INDETERMINADA -- equivalente exigido pelo AC16. */
    @Test
    void executar_falhaAutoritativaSobreEfetivacaoIndeterminada_concluiComFalhaEfetivacao() {
        FakeResultadoEfetivacaoPort fake = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.EFETIVACAO_INDETERMINADA);
        RegistrarResultadoEfetivacao usecase = usecase(fake, new FakeEntregaComClaim(true));

        ResultadoRegistroEfetivacao resultado = usecase.executar(
                EFETIVACAO_ID, new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_INEXISTENTE),
                Optional.empty(), AUTOR, AGORA);

        assertThat(resultado).isInstanceOf(ResultadoRegistroEfetivacao.Concluida.class);
        assertThat(((ResultadoRegistroEfetivacao.Concluida) resultado).statusResultante())
                .isEqualTo(StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO);
    }

    /** Conclusao tardia em sucesso sobre EFETIVACAO_INDETERMINADA -- ja provado por #0005, repetido aqui como regressao explicita do #0006. */
    @Test
    void executar_sucessoSobreEfetivacaoIndeterminada_concluiComEfetivada() {
        FakeResultadoEfetivacaoPort fake = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.EFETIVACAO_INDETERMINADA);
        RegistrarResultadoEfetivacao usecase = usecase(fake, new FakeEntregaComClaim(true));

        ResultadoRegistroEfetivacao resultado = usecase.executar(
                EFETIVACAO_ID, new ResultadoEfetivacaoRecebido.Sucesso(LIMITE_SOLICITADO_CONGELADO),
                Optional.of(new ProtocoloCore("PRT-TARDIO")), AUTOR, AGORA);

        assertThat(resultado).isInstanceOf(ResultadoRegistroEfetivacao.Concluida.class);
        assertThat(((ResultadoRegistroEfetivacao.Concluida) resultado).statusResultante())
                .isEqualTo(StatusSolicitacaoAumentoLimite.EFETIVADA);
    }

    // --- executarSobClaim: composicao fenced ----------------------------------------------------

    @Test
    void executarSobClaim_claimValido_concluiTerminalizaEDevolvePermanencia() {
        FakeResultadoEfetivacaoPort fake = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        FakeEntregaComClaim entrega = new FakeEntregaComClaim(true);
        RegistrarResultadoEfetivacao usecase = usecase(fake, entrega);

        ResultadoConclusaoDefinitiva resultado = usecase.executarSobClaim(
                claimDeTeste(), new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE),
                AUTOR, AGORA);

        assertThat(resultado).isInstanceOf(ResultadoConclusaoDefinitiva.Aplicado.class);
        assertThat(((ResultadoConclusaoDefinitiva.Aplicado) resultado).permanenciaEmAguardandoEfetivacao())
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(fake.chamadasDeRegistro).isEqualTo(1);
        assertThat(entrega.terminalizacoesFalhaDefinitiva).isEqualTo(1);
        assertThat(entrega.terminalizacoesConcorrentes).isZero();
    }

    @Test
    void executarSobClaim_claimObsoleto_descartaSemNenhumaEscrita() {
        FakeResultadoEfetivacaoPort fake = FakeResultadoEfetivacaoPort.naoTerminal(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        FakeEntregaComClaim entrega = new FakeEntregaComClaim(false);
        RegistrarResultadoEfetivacao usecase = usecase(fake, entrega);

        ResultadoConclusaoDefinitiva resultado = usecase.executarSobClaim(
                claimDeTeste(), new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE),
                AUTOR, AGORA);

        assertThat(resultado).isInstanceOf(ResultadoConclusaoDefinitiva.DescartadoClaimObsoleto.class);
        assertThat(fake.chamadasDeRegistro).isZero();
        assertThat(entrega.terminalizacoesFalhaDefinitiva).isZero();
        assertThat(entrega.terminalizacoesConcorrentes).isZero();
    }

    // --- Conclusao concorrente (#0005, guardrail normativo do Owner): duas direcoes -------------

    @Test
    void executarSobClaim_callbackDeSucessoJaConcluiuEfetivada_dispatcherPerdeAutoridadeDeEscrita() {
        // (a) dispatcher com claim prepara falha definitiva; callback de sucesso venceu e ja
        // commitou EFETIVADA antes desta chamada; dispatcher continua. Final: EFETIVADA
        // preservada, entrega ACEITA, sem motivo gravado, protocolo preservado, claim liberado.
        FakeResultadoEfetivacaoPort fake = FakeResultadoEfetivacaoPort.terminalEfetivada("PRT-1");
        FakeEntregaComClaim entrega = new FakeEntregaComClaim(true);

        ResultadoConclusaoDefinitiva resultado = usecase(fake, entrega).executarSobClaim(
                claimDeTeste(), new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_INEXISTENTE),
                AUTOR, AGORA);

        assertThat(resultado).isInstanceOf(ResultadoConclusaoDefinitiva.ConcluidaPorOutroCaminho.class);
        ResultadoConclusaoDefinitiva.ConcluidaPorOutroCaminho concluida = (ResultadoConclusaoDefinitiva.ConcluidaPorOutroCaminho) resultado;
        assertThat(concluida.terminalObservado()).isEqualTo(StatusSolicitacaoAumentoLimite.EFETIVADA);
        assertThat(concluida.contraditoria()).isTrue();
        assertThat(fake.status).isEqualTo(StatusSolicitacaoAumentoLimite.EFETIVADA);
        assertThat(fake.motivoFalha).isNull();
        assertThat(fake.protocoloCore).isEqualTo("PRT-1");
        assertThat(entrega.terminalizacoesFalhaDefinitiva).isZero();
        assertThat(entrega.terminalizacoesConcorrentes).isEqualTo(1);
        assertThat(entrega.ultimoTerminalConcorrente).isEqualTo(StatusSolicitacaoAumentoLimite.EFETIVADA);
    }

    @Test
    void executarSobClaim_callbackJaConcluiuFalhaComMesmoMotivo_naoContraditoriaMasAindaTerminalizaComoFalhaDefinitiva() {
        // (b) inverso: FALHA_EFETIVACAO ja persistida com o MESMO motivo que o dispatcher traria
        // -- concorrencia sem contradicao. Entrega ainda termina FALHA_DEFINITIVA (terminal
        // observado dita a entrega, nao o resultado perdedor).
        FakeResultadoEfetivacaoPort fake = FakeResultadoEfetivacaoPort.terminalFalha(
                MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE, null);
        FakeEntregaComClaim entrega = new FakeEntregaComClaim(true);

        ResultadoConclusaoDefinitiva resultado = usecase(fake, entrega).executarSobClaim(
                claimDeTeste(), new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE),
                AUTOR, AGORA);

        assertThat(resultado).isInstanceOf(ResultadoConclusaoDefinitiva.ConcluidaPorOutroCaminho.class);
        ResultadoConclusaoDefinitiva.ConcluidaPorOutroCaminho concluida = (ResultadoConclusaoDefinitiva.ConcluidaPorOutroCaminho) resultado;
        assertThat(concluida.terminalObservado()).isEqualTo(StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO);
        assertThat(concluida.contraditoria()).isFalse();
        assertThat(fake.status).isEqualTo(StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO);
        assertThat(fake.motivoFalha).isEqualTo(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE);
        assertThat(entrega.terminalizacoesFalhaDefinitiva).isZero();
        assertThat(entrega.terminalizacoesConcorrentes).isEqualTo(1);
        assertThat(entrega.ultimoTerminalConcorrente).isEqualTo(StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO);
    }

    @Test
    void executarSobClaim_callbackJaConcluiuFalhaComMotivoDiferente_contraditoriaMasTerminalizaComoFalhaDefinitiva() {
        FakeResultadoEfetivacaoPort fake = FakeResultadoEfetivacaoPort.terminalFalha(
                MotivoFalhaEfetivacao.CONTA_BLOQUEADA_NA_EFETIVACAO, null);
        FakeEntregaComClaim entrega = new FakeEntregaComClaim(true);

        ResultadoConclusaoDefinitiva resultado = usecase(fake, entrega).executarSobClaim(
                claimDeTeste(), new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE),
                AUTOR, AGORA);

        assertThat(resultado).isInstanceOf(ResultadoConclusaoDefinitiva.ConcluidaPorOutroCaminho.class);
        assertThat(((ResultadoConclusaoDefinitiva.ConcluidaPorOutroCaminho) resultado).contraditoria()).isTrue();
        assertThat(fake.motivoFalha).isEqualTo(MotivoFalhaEfetivacao.CONTA_BLOQUEADA_NA_EFETIVACAO);
        assertThat(entrega.terminalizacoesConcorrentes).isEqualTo(1);
        assertThat(entrega.ultimoTerminalConcorrente).isEqualTo(StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO);
    }

    /**
     * Fake comportamental de uma unica SolicitacaoAumentoLimite, chaveada por EfetivacaoId, que
     * delega a decisao de transicao ao dominio real e reproduz a MESMA classificacao terminal em
     * tres eixos (protocolo, resultado/motivo, limite efetivado) que
     * {@code JpaResultadoEfetivacaoAdapter} implementa contra PostgreSQL (S3).
     */
    private static final class FakeResultadoEfetivacaoPort implements ResultadoEfetivacaoPort {
        private static final Instant DECIDIDA_EM = AGORA.minus(Duration.ofMinutes(5));

        StatusSolicitacaoAumentoLimite status;
        String protocoloCore;
        MotivoFalhaEfetivacao motivoFalha;
        int chamadasDeRegistro = 0;

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

        static FakeResultadoEfetivacaoPort terminalFalha(MotivoFalhaEfetivacao motivo, String protocoloCore) {
            return new FakeResultadoEfetivacaoPort(StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO, protocoloCore, motivo);
        }

        @Override
        public ResultadoRegistroEfetivacao registrar(
                EfetivacaoId efetivacaoId, ResultadoEfetivacaoRecebido resultado, Optional<ProtocoloCore> protocoloInformado,
                AtorOperacao autor, Instant agora) {
            chamadasDeRegistro++;

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

        /**
         * #0006: mesma disciplina do metodo acima -- delega ao dominio real, idempotente sobre
         * ja-indeterminada e ja-terminal, exatamente como {@code JpaResultadoEfetivacaoAdapter}.
         */
        int chamadasDeIndeterminacao = 0;

        @Override
        public ResultadoIndeterminacao registrarIndeterminacao(EfetivacaoId efetivacaoId, Instant agora) {
            chamadasDeIndeterminacao++;
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

    /**
     * Fake minimo de {@link EntregasEfetivacaoPort} para a composicao de {@code executarSobClaim}:
     * so as tres operacoes de claim importam aqui -- o resto e {@code UnsupportedOperation}, pois
     * este teste nunca reclama nem reagenda (isso e materia de {@code EntregarInstrucoesEfetivacaoTest}).
     */
    private static final class FakeEntregaComClaim implements EntregasEfetivacaoPort {
        private final boolean claimValido;
        int terminalizacoesFalhaDefinitiva = 0;
        int terminalizacoesConcorrentes = 0;
        StatusSolicitacaoAumentoLimite ultimoTerminalConcorrente;

        FakeEntregaComClaim(boolean claimValido) {
            this.claimValido = claimValido;
        }

        @Override
        public boolean claimAindaValido(EntregaEfetivacaoReclamada claim) {
            return claimValido;
        }

        @Override
        public void terminalizarPorFalhaDefinitiva(EntregaEfetivacaoReclamada claim, Instant agora) {
            terminalizacoesFalhaDefinitiva++;
        }

        @Override
        public void terminalizarPorConclusaoConcorrente(
                EntregaEfetivacaoReclamada claim, StatusSolicitacaoAumentoLimite terminalObservado, Instant agora) {
            terminalizacoesConcorrentes++;
            ultimoTerminalConcorrente = terminalObservado;
        }

        @Override
        public ReclamacaoEntrega reclamarProxima(Instant agora, int maxTentativas, Duration lease) {
            throw new UnsupportedOperationException("nao usado neste teste");
        }

        @Override
        public ResultadoRegistroEntrega registrarAceite(EntregaEfetivacaoReclamada claim, ProtocoloCore protocoloCore, Instant agora) {
            throw new UnsupportedOperationException("nao usado neste teste");
        }

        @Override
        public ResultadoRegistroEntrega reagendar(
                EntregaEfetivacaoReclamada claim, Instant proximaTentativaEm, String erroSanitizado, Instant agora) {
            throw new UnsupportedOperationException("nao usado neste teste");
        }

        @Override
        public ResultadoRegistroEntrega marcarIndeterminada(EntregaEfetivacaoReclamada claim, String erroSanitizado, Instant agora) {
            throw new UnsupportedOperationException("nao usado neste teste");
        }
    }
}
