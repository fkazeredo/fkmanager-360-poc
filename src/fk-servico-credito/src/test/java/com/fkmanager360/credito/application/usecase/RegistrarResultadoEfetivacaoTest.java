package com.fkmanager360.credito.application.usecase;

import com.fkmanager360.credito.application.port.out.EntregaEfetivacaoReclamada;
import com.fkmanager360.credito.application.port.out.EntregasEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.IntencaoEfetivacao;
import com.fkmanager360.credito.application.port.out.ReclamacaoEntrega;
import com.fkmanager360.credito.application.port.out.ResultadoConclusaoDefinitiva;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoRecebido;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S2 (ADR-0018): {@code RegistrarResultadoEfetivacao} e o unico caso de uso de conclusao (ticket
 * #0004, Objetivo) -- este teste prova a orquestracao com fakes comportamentais pequenos que
 * reusam o MESMO {@link SolicitacaoAumentoLimite} de dominio (a maquina de estados ja exaustiva em
 * S1 desde #0003), nao uma segunda implementacao da regra de transicao. A entrada
 * {@code executarSobClaim} e provada aqui na composicao (fencing -> conclusao -> terminalizacao);
 * a atomicidade real contra PostgreSQL e provada em S3.
 */
class RegistrarResultadoEfetivacaoTest {

    private static final EfetivacaoId EFETIVACAO_ID = new EfetivacaoId(UUID.randomUUID());
    private static final AtorOperacao AUTOR = AtorSistema.CORE_LEGADO;
    private static final Instant AGORA = Instant.parse("2026-09-03T12:00:00Z");

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
                        new LimiteChequeEspecialVigente(500_000), new LimiteSolicitado(600_000),
                        new CorrelationId(UUID.randomUUID())),
                new SolicitacaoId(UUID.randomUUID()),
                1);
    }

    @Test
    void executar_solicitacaoAguardandoEfetivacao_concluiComFalhaDefinitiva() {
        FakeResultadoEfetivacaoPort fake = new FakeResultadoEfetivacaoPort(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        RegistrarResultadoEfetivacao usecase = usecase(fake, new FakeEntregaComClaim(true));

        ResultadoRegistroEfetivacao resultado = usecase.executar(
                EFETIVACAO_ID, new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE),
                AUTOR, AGORA);

        assertThat(resultado.concluiuAgora()).isTrue();
        assertThat(resultado.statusResultante()).isEqualTo(StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO);
        assertThat(resultado.permanenciaEmAguardandoEfetivacao()).isEqualTo(Duration.ofMinutes(5));
        assertThat(fake.chamadasDeRegistro).isEqualTo(1);
    }

    @Test
    void executar_duasVezesParaAMesmaEfetivacao_segundaChamadaENoOpIdempotente() {
        FakeResultadoEfetivacaoPort fake = new FakeResultadoEfetivacaoPort(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        RegistrarResultadoEfetivacao usecase = usecase(fake, new FakeEntregaComClaim(true));
        ResultadoEfetivacaoRecebido resultadoRecebido =
                new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE);

        ResultadoRegistroEfetivacao primeira = usecase.executar(EFETIVACAO_ID, resultadoRecebido, AUTOR, AGORA);
        ResultadoRegistroEfetivacao segunda = usecase.executar(EFETIVACAO_ID, resultadoRecebido, AUTOR, AGORA.plusSeconds(1));

        assertThat(primeira.concluiuAgora()).isTrue();
        assertThat(segunda.concluiuAgora()).isFalse();
        assertThat(segunda.statusResultante()).isEqualTo(StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO);
        assertThat(fake.chamadasDeRegistro).isEqualTo(2);
    }

    @Test
    void executar_solicitacaoJaEmOutroEstadoTerminal_naoReescreveENaoConcluiAgora() {
        FakeResultadoEfetivacaoPort fake = new FakeResultadoEfetivacaoPort(StatusSolicitacaoAumentoLimite.REJEITADA);
        RegistrarResultadoEfetivacao usecase = usecase(fake, new FakeEntregaComClaim(true));

        ResultadoRegistroEfetivacao resultado = usecase.executar(
                EFETIVACAO_ID, new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_INEXISTENTE),
                AUTOR, AGORA);

        assertThat(resultado.concluiuAgora()).isFalse();
        assertThat(resultado.statusResultante()).isEqualTo(StatusSolicitacaoAumentoLimite.REJEITADA);
    }

    @Test
    void executarSobClaim_claimValido_concluiTerminalizaEDevolvePermanencia() {
        FakeResultadoEfetivacaoPort fake = new FakeResultadoEfetivacaoPort(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        FakeEntregaComClaim entrega = new FakeEntregaComClaim(true);
        RegistrarResultadoEfetivacao usecase = usecase(fake, entrega);

        ResultadoConclusaoDefinitiva resultado = usecase.executarSobClaim(
                claimDeTeste(), new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE),
                AUTOR, AGORA);

        assertThat(resultado).isInstanceOf(ResultadoConclusaoDefinitiva.Aplicado.class);
        assertThat(((ResultadoConclusaoDefinitiva.Aplicado) resultado).permanenciaEmAguardandoEfetivacao())
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(fake.chamadasDeRegistro).isEqualTo(1);
        assertThat(entrega.terminalizacoes).isEqualTo(1);
    }

    @Test
    void executarSobClaim_claimObsoleto_descartaSemNenhumaEscrita() {
        FakeResultadoEfetivacaoPort fake = new FakeResultadoEfetivacaoPort(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        FakeEntregaComClaim entrega = new FakeEntregaComClaim(false);
        RegistrarResultadoEfetivacao usecase = usecase(fake, entrega);

        ResultadoConclusaoDefinitiva resultado = usecase.executarSobClaim(
                claimDeTeste(), new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE),
                AUTOR, AGORA);

        assertThat(resultado).isInstanceOf(ResultadoConclusaoDefinitiva.DescartadoClaimObsoleto.class);
        assertThat(fake.chamadasDeRegistro).isZero();
        assertThat(entrega.terminalizacoes).isZero();
    }

    @Test
    void executarSobClaim_conclusaoNaoAconteceuAgora_lancaInvarianteDoTicket() {
        // Claim valido mas solicitacao JA terminal: em #0004 isso e invariante quebrada (nenhum
        // outro caminho conclui antes do dispatcher) -- falha alto, e a transacao desfaz tudo.
        FakeResultadoEfetivacaoPort fake = new FakeResultadoEfetivacaoPort(StatusSolicitacaoAumentoLimite.REJEITADA);
        FakeEntregaComClaim entrega = new FakeEntregaComClaim(true);
        RegistrarResultadoEfetivacao usecase = usecase(fake, entrega);

        assertThatThrownBy(() -> usecase.executarSobClaim(
                claimDeTeste(), new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_INEXISTENTE),
                AUTOR, AGORA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invariante do #0004");
    }

    /**
     * Fake comportamental de uma unica SolicitacaoAumentoLimite, chaveada por EfetivacaoId, que
     * delega a decisao de transicao ao dominio real -- exatamente a mesma logica "ja terminal? nao
     * reescreve" que {@code JpaResultadoEfetivacaoAdapter} implementa contra PostgreSQL (S3).
     */
    private static final class FakeResultadoEfetivacaoPort implements ResultadoEfetivacaoPort {
        private static final Instant DECIDIDA_EM = AGORA.minus(Duration.ofMinutes(5));

        private final Map<EfetivacaoId, StatusSolicitacaoAumentoLimite> estados = new HashMap<>();
        int chamadasDeRegistro = 0;

        FakeResultadoEfetivacaoPort(StatusSolicitacaoAumentoLimite estadoInicial) {
            estados.put(EFETIVACAO_ID, estadoInicial);
        }

        @Override
        public ResultadoRegistroEfetivacao registrar(
                EfetivacaoId efetivacaoId, ResultadoEfetivacaoRecebido resultado, AtorOperacao autor, Instant agora) {
            chamadasDeRegistro++;
            StatusSolicitacaoAumentoLimite atual = estados.get(efetivacaoId);
            SolicitacaoAumentoLimite solicitacao = new SolicitacaoAumentoLimite(atual);

            if (atual.isTerminal()) {
                return new ResultadoRegistroEfetivacao(false, atual, null);
            }

            SolicitacaoAumentoLimite transicionada = solicitacao.transicionarPara(StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO);
            estados.put(efetivacaoId, transicionada.status());
            return new ResultadoRegistroEfetivacao(true, transicionada.status(), Duration.between(DECIDIDA_EM, agora));
        }
    }

    /**
     * Fake minimo de {@link EntregasEfetivacaoPort} para a composicao de {@code executarSobClaim}:
     * so as duas operacoes de claim importam aqui -- o resto e {@code UnsupportedOperation}, pois
     * este teste nunca reclama nem reagenda (isso e materia de {@code EntregarInstrucoesEfetivacaoTest}).
     */
    private static final class FakeEntregaComClaim implements EntregasEfetivacaoPort {
        private final boolean claimValido;
        int terminalizacoes = 0;

        FakeEntregaComClaim(boolean claimValido) {
            this.claimValido = claimValido;
        }

        @Override
        public boolean claimAindaValido(EntregaEfetivacaoReclamada claim) {
            return claimValido;
        }

        @Override
        public void terminalizarPorFalhaDefinitiva(EntregaEfetivacaoReclamada claim, Instant agora) {
            terminalizacoes++;
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
