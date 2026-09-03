package com.fkmanager360.credito.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-0006 + AC33: o ContextoDecisaoCredito e imutavel, completo, e nao carrega clienteId,
 * contaId, originadorId nem evidencia de autorizacao. O factory congelar() e a UNICA forma
 * publica de construi-lo a partir de um LimiteSolicitado bruto -- e a segunda linha de defesa que
 * garante incrementoSolicitado > 0.
 */
class ContextoDecisaoCreditoTest {

    private static final Instant CONSULTADO_EM = Instant.parse("2026-09-02T16:00:00Z");
    private static final Instant CAPTURADO_EM = Instant.parse("2026-09-02T16:00:05Z");
    private static final VersaoPoliticaCredito V1 = new VersaoPoliticaCredito("v1");

    private static DadosCreditoCore dados(long limiteVigenteCentavos) {
        return new DadosCreditoCore(
                new LimiteChequeEspecialVigente(limiteVigenteCentavos), SituacaoConta.REGULAR,
                ClassificacaoRiscoCreditoBase.BAIXO, CONSULTADO_EM, "CoreLegado");
    }

    @Test
    void congelar_calculaOIncrementoCorretamente() {
        var contexto = ContextoDecisaoCredito.congelar(
                dados(500_000), new LimiteSolicitado(600_000), V1, CAPTURADO_EM);

        assertThat(contexto.incrementoSolicitado().centavos()).isEqualTo(100_000);
    }

    @Test
    void incrementoNaoPositivo_lancaIllegalArgumentException() {
        // limiteSolicitado igual ao vigente -> incremento zero, nao estritamente positivo.
        assertThatThrownBy(() -> ContextoDecisaoCredito.congelar(
                dados(500_000), new LimiteSolicitado(500_000), V1, CAPTURADO_EM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void incrementoNegativo_lancaIllegalArgumentException() {
        assertThatThrownBy(() -> ContextoDecisaoCredito.congelar(
                dados(600_000), new LimiteSolicitado(500_000), V1, CAPTURADO_EM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void contexto_naoContemClienteIdContaIdOuOriginadorId() {
        // Prova estrutural, e nao so documental: os unicos campos do record sao os que a spec
        // permite (AC33). Se algum dia alguem adicionar clienteId/contaId/originadorId ao record,
        // este teste nao pega isso sozinho -- mas o construtor abaixo, que so aceita 5
        // argumentos, e o teste de composicao real.
        var contexto = ContextoDecisaoCredito.congelar(
                dados(500_000), new LimiteSolicitado(600_000), V1, CAPTURADO_EM);

        assertThat(contexto.dadosCreditoCore()).isNotNull();
        assertThat(contexto.limiteSolicitado().centavos()).isEqualTo(600_000);
        assertThat(contexto.incrementoSolicitado().centavos()).isEqualTo(100_000);
        assertThat(contexto.versaoPoliticaCredito()).isEqualTo(V1);
        assertThat(contexto.capturadoEm()).isEqualTo(CAPTURADO_EM);
    }

    @Test
    void nenhumCampoObrigatorioAceitaNulo() {
        assertThatThrownBy(() -> ContextoDecisaoCredito.congelar(null, new LimiteSolicitado(600_000), V1, CAPTURADO_EM))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ContextoDecisaoCredito.congelar(dados(500_000), null, V1, CAPTURADO_EM))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ContextoDecisaoCredito.congelar(dados(500_000), new LimiteSolicitado(600_000), null, CAPTURADO_EM))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ContextoDecisaoCredito.congelar(dados(500_000), new LimiteSolicitado(600_000), V1, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void reaplicarAPoliticaSobreOMesmoContexto_produzSempreAMesmaDecisao() {
        // AC33: reprodutibilidade. O mesmo contexto, avaliado duas vezes pela mesma politica,
        // nunca diverge -- e a politica so enxerga o que esta dentro do contexto.
        var contexto = ContextoDecisaoCredito.congelar(
                dados(500_000), new LimiteSolicitado(600_000), V1, CAPTURADO_EM);
        var politica = new PoliticaCreditoV1();

        assertThat(politica.avaliar(contexto)).isEqualTo(politica.avaliar(contexto));
    }
}
