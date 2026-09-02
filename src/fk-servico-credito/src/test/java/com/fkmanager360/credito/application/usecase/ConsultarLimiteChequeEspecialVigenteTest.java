package com.fkmanager360.credito.application.usecase;

import com.fkmanager360.credito.application.port.out.ContaNaoEncontradaException;
import com.fkmanager360.credito.application.port.out.CoreLegadoTimeoutException;
import com.fkmanager360.credito.application.port.out.DadosCreditoCorePort;
import com.fkmanager360.credito.application.port.out.DireitoDeAtendimentoAusenteException;
import com.fkmanager360.credito.application.port.out.DireitoDeAtendimentoPort;
import com.fkmanager360.credito.domain.ClassificacaoRiscoCreditoBase;
import com.fkmanager360.credito.domain.ClienteId;
import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.DadosCreditoCore;
import com.fkmanager360.credito.domain.LimiteChequeEspecialVigente;
import com.fkmanager360.credito.domain.SituacaoConta;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S2: orquestracao sem Spring, com fakes comportamentais pequenos (ADR-0018).
 *
 * <p>O teste central e o primeiro: <b>sem direito de atendimento, o fake do CoreLegado registra
 * zero invocacoes</b>. E a forma falsificavel do AC23 -- ausencia de efeito e parte da regra, e
 * nao detalhe de implementacao.
 */
class ConsultarLimiteChequeEspecialVigenteTest {

    private static final ClienteId CLIENTE_1 = new ClienteId("1");
    private static final ContaId CONTA_10001 = new ContaId("10001");
    private static final Instant CONSULTADO_EM = Instant.parse("2026-09-02T16:00:00Z");

    private static DadosCreditoCore dados(long centavos) {
        return new DadosCreditoCore(
                new LimiteChequeEspecialVigente(centavos),
                SituacaoConta.REGULAR,
                ClassificacaoRiscoCreditoBase.BAIXO,
                CONSULTADO_EM,
                "CoreLegado");
    }

    @Test
    void semDireitoDeAtendimento_recusa_eNenhumaConsultaAoCoreLegadoEEmitida() {
        var core = new CorePortFake(Optional.of(dados(500_000)));
        var useCase = new ConsultarLimiteChequeEspecialVigente(new DireitoNegadoFake(), core);

        assertThatThrownBy(() -> useCase.executar(CLIENTE_1, CONTA_10001))
                .isInstanceOf(DireitoDeAtendimentoAusenteException.class);

        assertThat(core.chamadas)
                .as("a verificacao do direito de atendimento precede qualquer acesso ao Core (AC23)")
                .isZero();
    }

    @Test
    void comDireitoDeAtendimento_devolveOLimiteQueOCoreReconheceAgora() {
        var useCase = new ConsultarLimiteChequeEspecialVigente(
                new DireitoConcedidoFake(), new CorePortFake(Optional.of(dados(500_000))));

        DadosCreditoCore resultado = useCase.executar(CLIENTE_1, CONTA_10001);

        assertThat(resultado.limiteChequeEspecialVigente().centavos()).isEqualTo(500_000);
        assertThat(resultado.consultadoEm()).isEqualTo(CONSULTADO_EM);
        assertThat(resultado.fonte()).isEqualTo("CoreLegado");
    }

    @Test
    void comDireitoDeAtendimento_oCoreEConsultadoExatamenteUmaVez() {
        var core = new CorePortFake(Optional.of(dados(500_000)));
        new ConsultarLimiteChequeEspecialVigente(new DireitoConcedidoFake(), core)
                .executar(CLIENTE_1, CONTA_10001);

        assertThat(core.chamadas).isEqualTo(1);
        assertThat(core.ultimaContaConsultada).isEqualTo(CONTA_10001);
    }

    @Test
    void contaDesconhecidaPeloCore_naoProduzLimite() {
        var useCase = new ConsultarLimiteChequeEspecialVigente(
                new DireitoConcedidoFake(), new CorePortFake(Optional.empty()));

        assertThatThrownBy(() -> useCase.executar(CLIENTE_1, CONTA_10001))
                .isInstanceOf(ContaNaoEncontradaException.class);
    }

    @Test
    void falhaDaAclDoCore_propagaTipada_semSerConfundidaComRegraDeNegocio() {
        DadosCreditoCorePort core = contaId -> {
            throw new CoreLegadoTimeoutException("tempo esgotado", null);
        };
        var useCase = new ConsultarLimiteChequeEspecialVigente(new DireitoConcedidoFake(), core);

        assertThatThrownBy(() -> useCase.executar(CLIENTE_1, CONTA_10001))
                .isInstanceOf(CoreLegadoTimeoutException.class);
    }

    @Test
    void limiteZero_eLimiteValido_naoAusenciaDeInformacao() {
        var useCase = new ConsultarLimiteChequeEspecialVigente(
                new DireitoConcedidoFake(), new CorePortFake(Optional.of(dados(0))));

        assertThat(useCase.executar(CLIENTE_1, CONTA_10001).limiteChequeEspecialVigente().centavos())
                .isZero();
    }

    // --- Fakes comportamentais -------------------------------------------------------------

    private static final class DireitoConcedidoFake implements DireitoDeAtendimentoPort {
        @Override
        public void confirmarDireitoDeAtendimento(ClienteId clienteId, ContaId contaId) {
            // Retorno normal e a confirmacao: nao ha nada a devolver.
        }
    }

    private static final class DireitoNegadoFake implements DireitoDeAtendimentoPort {
        @Override
        public void confirmarDireitoDeAtendimento(ClienteId clienteId, ContaId contaId) {
            throw new DireitoDeAtendimentoAusenteException("sem direito de atendimento atual");
        }
    }

    private static final class CorePortFake implements DadosCreditoCorePort {
        private final Optional<DadosCreditoCore> resposta;
        private int chamadas;
        private ContaId ultimaContaConsultada;

        CorePortFake(Optional<DadosCreditoCore> resposta) {
            this.resposta = resposta;
        }

        @Override
        public Optional<DadosCreditoCore> consultar(ContaId contaId) {
            chamadas++;
            ultimaContaConsultada = contaId;
            return resposta;
        }
    }
}
