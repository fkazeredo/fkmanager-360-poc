package com.fkmanager360.credito.application.usecase;

import com.fkmanager360.credito.application.port.out.CargaParaDecisao;
import com.fkmanager360.credito.application.port.out.EntradaHistorico;
import com.fkmanager360.credito.application.port.out.IntencaoEfetivacao;
import com.fkmanager360.credito.application.port.out.ResultadoAplicacaoDecisao;
import com.fkmanager360.credito.application.port.out.SolicitacoesAumentoLimitePort;
import com.fkmanager360.credito.domain.ClassificacaoRiscoCreditoBase;
import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.ContextoDecisaoCredito;
import com.fkmanager360.credito.domain.CorrelationId;
import com.fkmanager360.credito.domain.DadosCreditoCore;
import com.fkmanager360.credito.domain.DecisaoCredito;
import com.fkmanager360.credito.domain.LimiteChequeEspecialVigente;
import com.fkmanager360.credito.domain.LimiteSolicitado;
import com.fkmanager360.credito.domain.MotorDecisaoCredito;
import com.fkmanager360.credito.domain.MotivoDecisaoCredito;
import com.fkmanager360.credito.domain.PoliticaCredito;
import com.fkmanager360.credito.domain.PoliticaCreditoV1;
import com.fkmanager360.credito.domain.ResultadoDecisaoCredito;
import com.fkmanager360.credito.domain.SituacaoConta;
import com.fkmanager360.credito.domain.SolicitacaoId;
import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;
import com.fkmanager360.credito.domain.VersaoPoliticaCredito;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2: Fase 2 + Fase 3 do plano #0003, sem Spring, com um fake comportamental pequeno de
 * {@link SolicitacoesAumentoLimitePort} (ADR-0018) -- o mesmo estilo de
 * {@code ConsultarLimiteChequeEspecialVigenteTest}, mas com um pouco mais de estado porque a
 * porta tem tres metodos que precisam concordar entre si.
 */
class DecidirSolicitacaoAumentoLimiteTest {

    private static final Instant CONSULTADO_EM = Instant.parse("2026-09-02T16:00:00Z");
    private static final Instant CAPTURADO_EM = Instant.parse("2026-09-02T16:00:05Z");
    private static final Instant DECIDIDA_EM = Instant.parse("2026-09-02T16:05:00Z");
    private static final VersaoPoliticaCredito V1 = PoliticaCreditoV1.VERSAO;
    private static final ContaId CONTA = new ContaId("10001");
    private static final CorrelationId CORRELATION_ID = new CorrelationId(UUID.randomUUID());

    private static ContextoDecisaoCredito contextoAprovavel() {
        DadosCreditoCore dados = new DadosCreditoCore(
                new LimiteChequeEspecialVigente(500_000), SituacaoConta.REGULAR,
                ClassificacaoRiscoCreditoBase.BAIXO, CONSULTADO_EM, "CoreLegado");
        return ContextoDecisaoCredito.congelar(dados, new LimiteSolicitado(600_000), V1, CAPTURADO_EM);
    }

    private static ContextoDecisaoCredito contextoRejeitavel() {
        DadosCreditoCore dados = new DadosCreditoCore(
                new LimiteChequeEspecialVigente(500_000), SituacaoConta.IRREGULAR,
                ClassificacaoRiscoCreditoBase.BAIXO, CONSULTADO_EM, "CoreLegado");
        return ContextoDecisaoCredito.congelar(dados, new LimiteSolicitado(600_000), V1, CAPTURADO_EM);
    }

    @Test
    void solicitacaoAprovavel_transicionaParaAguardandoEfetivacao_eGeraIntencao() {
        var porta = new SolicitacoesAumentoLimitePortFake();
        SolicitacaoId id = porta.semear(StatusSolicitacaoAumentoLimite.SOLICITADA, contextoAprovavel(), CONTA, CORRELATION_ID);
        var motor = new MotorDecisaoCredito(List.of(new PoliticaCreditoV1()), V1);
        var useCase = new DecidirSolicitacaoAumentoLimite(porta, motor);

        var resultado = useCase.executar(id, DECIDIDA_EM);

        assertThat(resultado.status()).isEqualTo(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        assertThat(resultado.decisao().resultado()).isEqualTo(ResultadoDecisaoCredito.APROVADA);
        assertThat(porta.ultimaIntencaoRecebida).isNotNull();
    }

    @Test
    void solicitacaoRejeitavel_transicionaParaRejeitada_eNaoGeraIntencao() {
        var porta = new SolicitacoesAumentoLimitePortFake();
        SolicitacaoId id = porta.semear(StatusSolicitacaoAumentoLimite.SOLICITADA, contextoRejeitavel(), CONTA, CORRELATION_ID);
        var motor = new MotorDecisaoCredito(List.of(new PoliticaCreditoV1()), V1);
        var useCase = new DecidirSolicitacaoAumentoLimite(porta, motor);

        var resultado = useCase.executar(id, DECIDIDA_EM);

        assertThat(resultado.status()).isEqualTo(StatusSolicitacaoAumentoLimite.REJEITADA);
        assertThat(resultado.decisao().resultado()).isEqualTo(ResultadoDecisaoCredito.REJEITADA);
        assertThat(porta.ultimaIntencaoRecebida).isNull();
    }

    @Test
    void retomadaDeSolicitada_zeroChamadasRemotas_eUsaAVersaoCongelada_naoAVigente() {
        // O contexto foi congelado sob v1; o motor e construido com uma "vigente" hipotetica
        // diferente (v2, fake), provando que a retomada respeita a politica CONGELADA (D5).
        var versaoV2Fake = new VersaoPoliticaCredito("v2");
        PoliticaCredito politicaV2FakeSempreRejeita = new PoliticaCredito() {
            @Override
            public VersaoPoliticaCredito versao() {
                return versaoV2Fake;
            }

            @Override
            public MotivoDecisaoCredito avaliar(ContextoDecisaoCredito contexto) {
                return MotivoDecisaoCredito.FORA_DA_POLITICA_AUTOMATICA;
            }
        };
        var motor = new MotorDecisaoCredito(List.of(new PoliticaCreditoV1(), politicaV2FakeSempreRejeita), versaoV2Fake);

        var porta = new SolicitacoesAumentoLimitePortFake();
        SolicitacaoId id = porta.semear(StatusSolicitacaoAumentoLimite.SOLICITADA, contextoAprovavel(), CONTA, CORRELATION_ID);

        // Nota de design: este caso de uso nao recebe DireitoDeAtendimentoPort nem
        // DadosCreditoCorePort no construtor -- "zero chamadas remotas" e garantido pela propria
        // ASSINATURA da classe, e nao por um fake que poderia (mas nao precisa) ser chamado.
        var useCase = new DecidirSolicitacaoAumentoLimite(porta, motor);
        var resultado = useCase.executar(id, DECIDIDA_EM);

        // Se a retomada tivesse usado a vigente (v2 fake, sempre rejeita), o motivo seria
        // FORA_DA_POLITICA_AUTOMATICA. Usando a versao congelada (v1), a mesma conta/valores
        // aprovam.
        assertThat(resultado.decisao().motivo()).isEqualTo(MotivoDecisaoCredito.DENTRO_DA_POLITICA_AUTOMATICA);
        assertThat(resultado.decisao().versaoPoliticaCredito()).isEqualTo(V1);
    }

    @Test
    void replayDeSolicitacaoJaDecidida_naoRecalculaDecisao_eNaoDuplicaHistorico() {
        var porta = new SolicitacoesAumentoLimitePortFake();
        SolicitacaoId id = porta.semear(StatusSolicitacaoAumentoLimite.SOLICITADA, contextoAprovavel(), CONTA, CORRELATION_ID);
        var motor = new MotorDecisaoCredito(List.of(new PoliticaCreditoV1()), V1);
        var useCase = new DecidirSolicitacaoAumentoLimite(porta, motor);

        var primeiraChamada = useCase.executar(id, DECIDIDA_EM);
        var segundaChamada = useCase.executar(id, DECIDIDA_EM.plusSeconds(30));

        assertThat(segundaChamada.decisao()).isEqualTo(primeiraChamada.decisao());
        assertThat(segundaChamada.status()).isEqualTo(primeiraChamada.status());
        // Apenas a primeira chamada escreveu de fato; a segunda encontrou a solicitacao ja
        // decidida e a porta devolveu decidiuAgora=false sem reescrever nada.
        assertThat(porta.chamadasAplicarDecisaoComEscrita).isEqualTo(1);
        assertThat(porta.chamadasAplicarDecisaoSemEscrita).isEqualTo(1);
        assertThat(porta.entradasHistoricoGravadas).isEqualTo(1);
    }

    @Test
    void fatoIdDaEntradaDeHistorico_eDeterministicoAPartirDaSolicitacaoId() {
        var porta = new SolicitacoesAumentoLimitePortFake();
        SolicitacaoId id = porta.semear(StatusSolicitacaoAumentoLimite.SOLICITADA, contextoAprovavel(), CONTA, CORRELATION_ID);
        var motor = new MotorDecisaoCredito(List.of(new PoliticaCreditoV1()), V1);
        new DecidirSolicitacaoAumentoLimite(porta, motor).executar(id, DECIDIDA_EM);

        assertThat(porta.ultimaEntradaRecebida.fatoId()).isEqualTo("DECISAO:" + id.valor());
    }

    // --- Fake comportamental ----------------------------------------------------------------

    /**
     * Fake em memoria de SolicitacoesAumentoLimitePort com semantica proxima da real: mantem o
     * status por SolicitacaoId e so "escreve" (transiciona, grava decisao e historico) quando o
     * status atual e SOLICITADA -- exatamente o contrato descrito no Javadoc da porta.
     */
    private static final class SolicitacoesAumentoLimitePortFake implements SolicitacoesAumentoLimitePort {
        private final Map<SolicitacaoId, StatusSolicitacaoAumentoLimite> status = new LinkedHashMap<>();
        private final Map<SolicitacaoId, ContextoDecisaoCredito> contextos = new LinkedHashMap<>();
        private final Map<SolicitacaoId, ContaId> contas = new LinkedHashMap<>();
        private final Map<SolicitacaoId, CorrelationId> correlationIds = new LinkedHashMap<>();
        private final Map<SolicitacaoId, DecisaoCredito> decisoes = new LinkedHashMap<>();
        private final Set<String> fatoIdsGravados = new LinkedHashSet<>();

        int chamadasAplicarDecisaoComEscrita;
        int chamadasAplicarDecisaoSemEscrita;
        int entradasHistoricoGravadas;
        IntencaoEfetivacao ultimaIntencaoRecebida;
        EntradaHistorico ultimaEntradaRecebida;

        SolicitacaoId semear(StatusSolicitacaoAumentoLimite statusInicial, ContextoDecisaoCredito contexto,
                              ContaId contaId, CorrelationId correlationId) {
            SolicitacaoId id = new SolicitacaoId(UUID.randomUUID());
            status.put(id, statusInicial);
            contextos.put(id, contexto);
            contas.put(id, contaId);
            correlationIds.put(id, correlationId);
            return id;
        }

        @Override
        public com.fkmanager360.credito.application.port.out.ResultadoRegistroSolicitacao registrar(
                com.fkmanager360.credito.application.port.out.NovaSolicitacaoAumentoLimite dados) {
            throw new UnsupportedOperationException("nao usado em DecidirSolicitacaoAumentoLimiteTest");
        }

        @Override
        public CargaParaDecisao carregarParaDecisao(SolicitacaoId id) {
            return new CargaParaDecisao(status.get(id), contextos.get(id), contas.get(id), correlationIds.get(id),
                    Instant.parse("2026-09-03T10:00:00Z"));
        }

        @Override
        public ResultadoAplicacaoDecisao aplicarDecisao(
                SolicitacaoId id, DecisaoCredito decisao, IntencaoEfetivacao intencaoOuNull, EntradaHistorico entrada) {
            ultimaIntencaoRecebida = intencaoOuNull;
            ultimaEntradaRecebida = entrada;

            StatusSolicitacaoAumentoLimite atual = status.get(id);
            if (atual == StatusSolicitacaoAumentoLimite.SOLICITADA) {
                chamadasAplicarDecisaoComEscrita++;
                StatusSolicitacaoAumentoLimite novoStatus = decisao.resultado() == ResultadoDecisaoCredito.APROVADA
                        ? StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO
                        : StatusSolicitacaoAumentoLimite.REJEITADA;
                status.put(id, novoStatus);
                decisoes.put(id, decisao);
                if (fatoIdsGravados.add(entrada.fatoId())) {
                    entradasHistoricoGravadas++;
                }
                return new ResultadoAplicacaoDecisao(true, novoStatus, decisao);
            }

            chamadasAplicarDecisaoSemEscrita++;
            return new ResultadoAplicacaoDecisao(false, atual, decisoes.get(id));
        }
    }
}
