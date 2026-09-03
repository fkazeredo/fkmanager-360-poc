package com.fkmanager360.credito.application.usecase;

import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoRecebido;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroEfetivacao;
import com.fkmanager360.credito.domain.AtorOperacao;
import com.fkmanager360.credito.domain.AtorSistema;
import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.MotivoFalhaEfetivacao;
import com.fkmanager360.credito.domain.SolicitacaoAumentoLimite;
import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2 (ADR-0018): {@code RegistrarResultadoEfetivacao} e o unico caso de uso de conclusao (ticket
 * #0004, Objetivo) -- este teste prova a orquestracao com um fake comportamental pequeno que
 * reusa o MESMO {@link SolicitacaoAumentoLimite} de dominio (a maquina de estados ja exaustiva em
 * S1 desde #0003), nao uma segunda implementacao da regra de transicao.
 */
class RegistrarResultadoEfetivacaoTest {

    private static final EfetivacaoId EFETIVACAO_ID = new EfetivacaoId(UUID.randomUUID());
    private static final AtorOperacao AUTOR = AtorSistema.CORE_LEGADO;
    private static final Instant AGORA = Instant.parse("2026-09-03T12:00:00Z");

    @Test
    void executar_solicitacaoAguardandoEfetivacao_concluiComFalhaDefinitiva() {
        FakeResultadoEfetivacaoPort fake = new FakeResultadoEfetivacaoPort(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        RegistrarResultadoEfetivacao usecase = new RegistrarResultadoEfetivacao(fake);

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
        RegistrarResultadoEfetivacao usecase = new RegistrarResultadoEfetivacao(fake);
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
        RegistrarResultadoEfetivacao usecase = new RegistrarResultadoEfetivacao(fake);

        ResultadoRegistroEfetivacao resultado = usecase.executar(
                EFETIVACAO_ID, new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_INEXISTENTE),
                AUTOR, AGORA);

        assertThat(resultado.concluiuAgora()).isFalse();
        assertThat(resultado.statusResultante()).isEqualTo(StatusSolicitacaoAumentoLimite.REJEITADA);
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
}
