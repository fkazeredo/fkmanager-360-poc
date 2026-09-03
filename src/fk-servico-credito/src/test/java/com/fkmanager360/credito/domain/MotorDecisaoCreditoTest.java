package com.fkmanager360.credito.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S1: o guardrail critico do plano (D5) -- decidir() resolve pela versao do CONTEXTO, nunca pela
 * vigente do motor -- e as duas falhas explicitas de versao indisponivel (na construcao e na
 * decisao).
 */
class MotorDecisaoCreditoTest {

    private static final Instant CONSULTADO_EM = Instant.parse("2026-09-02T16:00:00Z");
    private static final Instant CAPTURADO_EM = Instant.parse("2026-09-02T16:00:05Z");
    private static final Instant DECIDIDA_EM = Instant.parse("2026-09-02T16:05:00Z");
    private static final VersaoPoliticaCredito V1 = new VersaoPoliticaCredito("v1");
    private static final VersaoPoliticaCredito V2 = new VersaoPoliticaCredito("v2");

    /** Politica fake v2 com comportamento propositalmente diferente de V1, so para este teste. */
    private static final class PoliticaFakeV2 implements PoliticaCredito {
        @Override
        public VersaoPoliticaCredito versao() {
            return V2;
        }

        @Override
        public MotivoDecisaoCredito avaliar(ContextoDecisaoCredito contexto) {
            // V2 fake sempre rejeita por fora da politica, para ser distinguivel de V1 no teste.
            return MotivoDecisaoCredito.FORA_DA_POLITICA_AUTOMATICA;
        }
    }

    private static ContextoDecisaoCredito contextoAprovavelPorV1() {
        DadosCreditoCore dados = new DadosCreditoCore(
                new LimiteChequeEspecialVigente(500_000), SituacaoConta.REGULAR,
                ClassificacaoRiscoCreditoBase.BAIXO, CONSULTADO_EM, "CoreLegado");
        return ContextoDecisaoCredito.congelar(dados, new LimiteSolicitado(600_000), V1, CAPTURADO_EM);
    }

    @Test
    void decidir_resolveAPoliticaPelaVersaoDoContexto_naoPelaVigenteDoMotor() {
        // Motor construido com vigente = v2 (a fake), mas o contexto foi capturado sob v1.
        var motor = new MotorDecisaoCredito(List.of(new PoliticaCreditoV1(), new PoliticaFakeV2()), V2);

        var decisao = motor.decidir(contextoAprovavelPorV1(), DECIDIDA_EM);

        // Se o motor tivesse usado a vigente (v2 fake), o motivo seria FORA_DA_POLITICA_AUTOMATICA.
        // Usando a versao do contexto (v1), o motivo e DENTRO_DA_POLITICA_AUTOMATICA.
        assertThat(decisao.motivo()).isEqualTo(MotivoDecisaoCredito.DENTRO_DA_POLITICA_AUTOMATICA);
        assertThat(decisao.versaoPoliticaCredito()).isEqualTo(V1);
    }

    @Test
    void construcao_falhaSeVersaoVigenteNaoEstiverEntreAsConhecidas() {
        assertThatThrownBy(() -> new MotorDecisaoCredito(List.of(new PoliticaCreditoV1()), V2))
                .isInstanceOf(VersaoPoliticaCreditoIndisponivelException.class);
    }

    @Test
    void decidir_comVersaoDeContextoSemImplementacaoRegistrada_lancaVersaoIndisponivel() {
        var motor = new MotorDecisaoCredito(List.of(new PoliticaCreditoV1()), V1);

        DadosCreditoCore dados = new DadosCreditoCore(
                new LimiteChequeEspecialVigente(500_000), SituacaoConta.REGULAR,
                ClassificacaoRiscoCreditoBase.BAIXO, CONSULTADO_EM, "CoreLegado");
        var contextoV2 = ContextoDecisaoCredito.congelar(dados, new LimiteSolicitado(600_000), V2, CAPTURADO_EM);

        assertThatThrownBy(() -> motor.decidir(contextoV2, DECIDIDA_EM))
                .isInstanceOf(VersaoPoliticaCreditoIndisponivelException.class);
    }

    @Test
    void versaoVigente_devolveAVersaoConfiguradaNaConstrucao() {
        var motor = new MotorDecisaoCredito(List.of(new PoliticaCreditoV1()), V1);
        assertThat(motor.versaoVigente()).isEqualTo(V1);
    }

    @Test
    void validarVersaoVigenteDisponivel_naoLancaQuandoConstrucaoJaFoiBemSucedida() {
        var motor = new MotorDecisaoCredito(List.of(new PoliticaCreditoV1()), V1);
        // Chamada explicita nao deve lancar -- a validacao ja passou na construcao.
        motor.validarVersaoVigenteDisponivel();
    }

    @Test
    void decisaoProduzida_temAutorMotorDecisaoCredito() {
        var motor = new MotorDecisaoCredito(List.of(new PoliticaCreditoV1()), V1);
        var decisao = motor.decidir(contextoAprovavelPorV1(), DECIDIDA_EM);

        assertThat(decisao.autor()).isEqualTo(AtorSistema.MOTOR_DECISAO_CREDITO);
        assertThat(decisao.decididaEm()).isEqualTo(DECIDIDA_EM);
    }

    @Test
    void resultadoDaDecisao_eCoerenteComOMotivo() {
        var motor = new MotorDecisaoCredito(List.of(new PoliticaCreditoV1()), V1);
        var decisao = motor.decidir(contextoAprovavelPorV1(), DECIDIDA_EM);

        assertThat(decisao.resultado()).isEqualTo(decisao.motivo().resultado());
    }
}
