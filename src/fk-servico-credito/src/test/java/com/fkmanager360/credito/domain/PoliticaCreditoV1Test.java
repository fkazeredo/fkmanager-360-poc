package com.fkmanager360.credito.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S1: PoliticaCredito v1 nas quatro faixas, incluindo as fronteiras exatas (spec, secao
 * "PoliticaCredito v1"; ticket #0003, Testing).
 */
class PoliticaCreditoV1Test {

    private static final Instant CONSULTADO_EM = Instant.parse("2026-09-02T16:00:00Z");
    private static final Instant CAPTURADO_EM = Instant.parse("2026-09-02T16:00:05Z");
    private static final VersaoPoliticaCredito V1 = new VersaoPoliticaCredito("v1");
    private static final PoliticaCreditoV1 POLITICA = new PoliticaCreditoV1();

    private static ContextoDecisaoCredito contexto(
            SituacaoConta situacao, ClassificacaoRiscoCreditoBase risco, long vigente, long solicitado) {
        DadosCreditoCore dados = new DadosCreditoCore(
                new LimiteChequeEspecialVigente(vigente), situacao, risco, CONSULTADO_EM, "CoreLegado");
        return ContextoDecisaoCredito.congelar(dados, new LimiteSolicitado(solicitado), V1, CAPTURADO_EM);
    }

    @Test
    void versaoDaPoliticaV1_ev1() {
        assertThat(POLITICA.versao()).isEqualTo(V1);
    }

    @Test
    void contaIrregular_eSempreRejeitadaPorContaNaoElegivel_mesmoComValoresDentroDaFaixa() {
        var ctx = contexto(SituacaoConta.IRREGULAR, ClassificacaoRiscoCreditoBase.BAIXO, 500_000, 600_000);
        assertThat(POLITICA.avaliar(ctx)).isEqualTo(MotivoDecisaoCredito.CONTA_NAO_ELEGIVEL);
    }

    @Test
    void riscoAlto_eSempreRejeitadoPorPerfilIncompativel_mesmoComValoresDentroDaFaixa() {
        var ctx = contexto(SituacaoConta.REGULAR, ClassificacaoRiscoCreditoBase.ALTO, 500_000, 600_000);
        assertThat(POLITICA.avaliar(ctx)).isEqualTo(MotivoDecisaoCredito.PERFIL_RISCO_INCOMPATIVEL);
    }

    @Test
    void contaIrregularEriscoAlto_motivoEContaNaoElegivel_primeiraRegraPrecede() {
        // Ordem de precedencia: CONTA_NAO_ELEGIVEL (regra 1) precede PERFIL_RISCO_INCOMPATIVEL (regra 2).
        var ctx = contexto(SituacaoConta.IRREGULAR, ClassificacaoRiscoCreditoBase.ALTO, 500_000, 600_000);
        assertThat(POLITICA.avaliar(ctx)).isEqualTo(MotivoDecisaoCredito.CONTA_NAO_ELEGIVEL);
    }

    @Test
    void riscoBaixo_limiteExatamenteNoTeto_incrementoExatamenteNoTeto_eAprovada() {
        // vigente 800.000 (R$8.000,00); solicitado 1.000.000 (R$10.000,00) -> incremento 200.000 (R$2.000,00).
        var ctx = contexto(SituacaoConta.REGULAR, ClassificacaoRiscoCreditoBase.BAIXO, 800_000, 1_000_000);
        assertThat(POLITICA.avaliar(ctx)).isEqualTo(MotivoDecisaoCredito.DENTRO_DA_POLITICA_AUTOMATICA);
    }

    @Test
    void riscoMedio_limiteExatamenteNoTeto_incrementoExatamenteNoTeto_eAprovada() {
        var ctx = contexto(SituacaoConta.REGULAR, ClassificacaoRiscoCreditoBase.MEDIO, 800_000, 1_000_000);
        assertThat(POLITICA.avaliar(ctx)).isEqualTo(MotivoDecisaoCredito.DENTRO_DA_POLITICA_AUTOMATICA);
    }

    @Test
    void limiteSolicitadoUmCentavoAcimaDoTeto_eForaDaPolitica_mesmoComIncrementoDentroDoTeto() {
        // Isola a variavel: vigente = 900.000 mantem o incremento em 100.001 (bem dentro do teto
        // de 200.000), de modo que APENAS o teto de limiteSolicitado (1.000.000) seja violado.
        var ctx = contexto(SituacaoConta.REGULAR, ClassificacaoRiscoCreditoBase.BAIXO, 900_000, 1_000_001);
        assertThat(POLITICA.avaliar(ctx)).isEqualTo(MotivoDecisaoCredito.FORA_DA_POLITICA_AUTOMATICA);
    }

    @Test
    void incrementoUmCentavoAcimaDoTeto_eForaDaPolitica() {
        // limiteSolicitado dentro do teto (1.000.000) mas incremento de 200.001 -> vigente = 799.999.
        var ctx = contexto(SituacaoConta.REGULAR, ClassificacaoRiscoCreditoBase.BAIXO, 799_999, 1_000_000);
        assertThat(POLITICA.avaliar(ctx)).isEqualTo(MotivoDecisaoCredito.FORA_DA_POLITICA_AUTOMATICA);
    }

    @Test
    void valoresBemAcimaDaFaixa_eForaDaPolitica_naoOutroMotivo() {
        var ctx = contexto(SituacaoConta.REGULAR, ClassificacaoRiscoCreditoBase.MEDIO, 500_000, 5_000_000);
        assertThat(POLITICA.avaliar(ctx)).isEqualTo(MotivoDecisaoCredito.FORA_DA_POLITICA_AUTOMATICA);
    }

    @Test
    void politicaETotal_todaCombinacaoProduzUmDosQuatroMotivos() {
        for (SituacaoConta situacao : SituacaoConta.values()) {
            for (ClassificacaoRiscoCreditoBase risco : ClassificacaoRiscoCreditoBase.values()) {
                var ctx = contexto(situacao, risco, 100_000, 200_000);
                assertThat(POLITICA.avaliar(ctx)).isNotNull();
            }
        }
    }
}
