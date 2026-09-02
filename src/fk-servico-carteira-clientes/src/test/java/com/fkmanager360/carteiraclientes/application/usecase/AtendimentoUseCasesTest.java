package com.fkmanager360.carteiraclientes.application.usecase;

import com.fkmanager360.carteiraclientes.application.port.out.CoreLegadoUnavailableException;
import com.fkmanager360.carteiraclientes.application.port.out.ContasClientePort;
import com.fkmanager360.carteiraclientes.application.port.out.DadosMestresClientePort;
import com.fkmanager360.carteiraclientes.application.port.out.VinculosCarteiraPort;
import com.fkmanager360.carteiraclientes.domain.ClienteId;
import com.fkmanager360.carteiraclientes.domain.ContaCorrente;
import com.fkmanager360.carteiraclientes.domain.ContaId;
import com.fkmanager360.carteiraclientes.domain.ContextoAtendimento;
import com.fkmanager360.carteiraclientes.domain.DadosMestresCliente;
import com.fkmanager360.carteiraclientes.domain.GerenteId;
import com.fkmanager360.carteiraclientes.domain.PageResult;
import com.fkmanager360.carteiraclientes.domain.Pagination;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S2: orquestracao provada sem Spring, com fakes comportamentais pequenos (ADR-0018). O que
 * estes testes falsificam nao e "o metodo X foi chamado", e sim o efeito externo observavel que
 * o AC23 exige: <b>sem direito de atendimento, nenhuma chamada ao CoreLegado e emitida</b> -- e
 * por isso os fakes das portas de ACL contam invocacoes.
 *
 * <p>Cobre os tres casos de uso de atendimento: {@link ListarContasDoCliente},
 * {@link ConfirmarDireitoDeAtendimento} -- o primitivo de autorizacao que servico-credito consome
 * -- e {@link ConsultarContextoAtendimento}, que o compoe com dados mestres para a tela do
 * bff-gerente (AC30, achado I4 do review de #0002).
 */
class AtendimentoUseCasesTest {

    private static final GerenteId GERENTE_A = new GerenteId("gerente.a");
    private static final ClienteId CLIENTE_1 = new ClienteId("1");
    private static final ContaId CONTA_10001 = new ContaId("10001");

    // --- ListarContasDoCliente -------------------------------------------------------------

    @Test
    void listarContas_semDireitoDeAtendimentoAtual_recusa_eNaoConsultaOCore() {
        var contas = new ContasFake(List.of(new ContaCorrente(CONTA_10001, "0001")));
        var useCase = new ListarContasDoCliente(new VinculosFake(Set.of()), contas);

        assertThatThrownBy(() -> useCase.executar(GERENTE_A, CLIENTE_1))
                .isInstanceOf(DireitoDeAtendimentoAusenteException.class);

        assertThat(contas.chamadas)
                .as("nenhuma chamada ao CoreLegado pode ser emitida sem direito de atendimento (AC23)")
                .isZero();
    }

    @Test
    void listarContas_comDireitoAtual_devolveAsContasQueOCoreReconhece() {
        var contas = new ContasFake(List.of(
                new ContaCorrente(CONTA_10001, "0001"),
                new ContaCorrente(new ContaId("10002"), "0001")));
        var useCase = new ListarContasDoCliente(new VinculosFake(Set.of("gerente.a:1")), contas);

        assertThat(useCase.executar(GERENTE_A, CLIENTE_1))
                .extracting(conta -> conta.contaId().valor())
                .containsExactly("10001", "10002");
        assertThat(contas.chamadas).isEqualTo(1);
    }

    @Test
    void listarContas_clienteDaCarteiraSemContaNoCore_devolveListaVazia_naoErro() {
        var useCase = new ListarContasDoCliente(
                new VinculosFake(Set.of("gerente.a:1")), new ContasFake(List.of()));

        assertThat(useCase.executar(GERENTE_A, CLIENTE_1)).isEmpty();
    }

    // --- ConfirmarDireitoDeAtendimento (primitivo de autorizacao, sem dados cadastrais) -----

    @Test
    void confirmarDireito_semDireitoDeAtendimentoAtual_recusa_eNaoConsultaOCore() {
        var contas = new ContasFake(List.of(new ContaCorrente(CONTA_10001, "0001")));
        var useCase = new ConfirmarDireitoDeAtendimento(new VinculosFake(Set.of()), contas);

        assertThatThrownBy(() -> useCase.executar(GERENTE_A, CLIENTE_1, CONTA_10001))
                .isInstanceOf(DireitoDeAtendimentoAusenteException.class);

        assertThat(contas.chamadas)
                .as("a autorizacao precede qualquer acesso ao Core, em toda consulta por conta (AC23)")
                .isZero();
    }

    @Test
    void confirmarDireito_comDireitoAtual_devolveAContaConfirmada() {
        var useCase = new ConfirmarDireitoDeAtendimento(
                new VinculosFake(Set.of("gerente.a:1")),
                new ContasFake(List.of(new ContaCorrente(CONTA_10001, "0001"))));

        ContaCorrente conta = useCase.executar(GERENTE_A, CLIENTE_1, CONTA_10001);

        assertThat(conta).isEqualTo(new ContaCorrente(CONTA_10001, "0001"));
    }

    @Test
    void confirmarDireito_contaQueNaoPertenceAoClienteAutorizado_naoConfirma() {
        var useCase = new ConfirmarDireitoDeAtendimento(
                new VinculosFake(Set.of("gerente.a:1")),
                new ContasFake(List.of(new ContaCorrente(CONTA_10001, "0001"))));

        assertThatThrownBy(() -> useCase.executar(GERENTE_A, CLIENTE_1, new ContaId("20001")))
                .isInstanceOf(ContaNaoEncontradaException.class);
    }

    // --- ConsultarContextoAtendimento (composicao para a tela do bff-gerente) --------------

    @Test
    void contextoAtendimento_semDireitoDeAtendimentoAtual_recusa_eNaoConsultaOCoreNemDadosMestres() {
        var contas = new ContasFake(List.of(new ContaCorrente(CONTA_10001, "0001")));
        var dadosMestres = new DadosMestresFake();
        var useCase = new ConsultarContextoAtendimento(
                new ConfirmarDireitoDeAtendimento(new VinculosFake(Set.of()), contas), dadosMestres);

        assertThatThrownBy(() -> useCase.executar(GERENTE_A, CLIENTE_1, CONTA_10001))
                .isInstanceOf(DireitoDeAtendimentoAusenteException.class);

        assertThat(contas.chamadas)
                .as("a autorizacao precede qualquer acesso ao Core, em toda consulta por conta (AC23)")
                .isZero();
        assertThat(dadosMestres.chamadas).isZero();
    }

    @Test
    void contextoAtendimento_comDireitoAtual_compoeClienteEConta() {
        var useCase = new ConsultarContextoAtendimento(
                new ConfirmarDireitoDeAtendimento(
                        new VinculosFake(Set.of("gerente.a:1")),
                        new ContasFake(List.of(new ContaCorrente(CONTA_10001, "0001")))),
                new DadosMestresFake());

        ContextoAtendimento contexto = useCase.executar(GERENTE_A, CLIENTE_1, CONTA_10001);

        assertThat(contexto.clienteId()).isEqualTo(CLIENTE_1);
        assertThat(contexto.conta()).isEqualTo(new ContaCorrente(CONTA_10001, "0001"));
        assertThat(contexto.dadosMestres().nome()).isEqualTo("ANA BEATRIZ SOUZA");
    }

    @Test
    void contextoAtendimento_contaQueNaoPertenceAoClienteAutorizado_naoProduzContexto() {
        // O gerente tem direito sobre o Cliente 1, e manda uma conta que nao e dele. Quem afirma
        // a quem a conta pertence e o Core, nunca o payload de quem chamou.
        var useCase = new ConsultarContextoAtendimento(
                new ConfirmarDireitoDeAtendimento(
                        new VinculosFake(Set.of("gerente.a:1")),
                        new ContasFake(List.of(new ContaCorrente(CONTA_10001, "0001")))),
                new DadosMestresFake());

        assertThatThrownBy(() -> useCase.executar(GERENTE_A, CLIENTE_1, new ContaId("20001")))
                .isInstanceOf(ContaNaoEncontradaException.class);
    }

    @Test
    void contextoAtendimento_clienteNaoResolvidoNoLote_devolveIndisponivel_naoErro() {
        // COD-RET 104 do host: o Cliente simplesmente nao veio na resposta do lote. Ausencia
        // deliberada do mapa -- diferente de a ACL ter lancado uma excecao (proximo teste).
        var useCase = new ConsultarContextoAtendimento(
                new ConfirmarDireitoDeAtendimento(
                        new VinculosFake(Set.of("gerente.a:1")),
                        new ContasFake(List.of(new ContaCorrente(CONTA_10001, "0001")))),
                new DadosMestresFake(Map.of()));

        ContextoAtendimento contexto = useCase.executar(GERENTE_A, CLIENTE_1, CONTA_10001);

        assertThat(contexto.dadosMestres()).isEqualTo(DadosMestresCliente.indisponivel());
        assertThat(contexto.conta().contaId()).isEqualTo(CONTA_10001);
    }

    @Test
    void contextoAtendimento_aclDeDadosMestresIndisponivel_propagaAFalha_naoEngole() {
        // Diferente do caso anterior: aqui a ACL de dados mestres FALHA (indisponibilidade
        // transitoria do CoreLegado), nao apenas "cliente ausente do lote". Este caso de uso
        // serve a composicao rica do bff-gerente, que de fato precisa do nome e do CPF -- entao
        // a falha deve propagar (502/503/504 na borda), e nao ser silenciosamente mascarada como
        // "indisponivel()". Fechar esta lacuna nao reabre o I4: servico-credito nao usa mais este
        // caso de uso, e por isso nunca fica acoplado a esta dependencia.
        var useCase = new ConsultarContextoAtendimento(
                new ConfirmarDireitoDeAtendimento(
                        new VinculosFake(Set.of("gerente.a:1")),
                        new ContasFake(List.of(new ContaCorrente(CONTA_10001, "0001")))),
                clienteIds -> {
                    throw new CoreLegadoUnavailableException("CoreLegado indisponivel", null);
                });

        assertThatThrownBy(() -> useCase.executar(GERENTE_A, CLIENTE_1, CONTA_10001))
                .isInstanceOf(CoreLegadoUnavailableException.class);
    }

    // --- Fakes comportamentais -------------------------------------------------------------

    private static final class VinculosFake implements VinculosCarteiraPort {
        private final Set<String> vinculos;

        VinculosFake(Set<String> vinculos) {
            this.vinculos = vinculos;
        }

        @Override
        public PageResult<ClienteId> findPage(GerenteId gerenteId, Pagination pagination) {
            throw new UnsupportedOperationException("nao exercitado por este teste");
        }

        @Override
        public boolean existeVinculo(GerenteId gerenteId, ClienteId clienteId) {
            return vinculos.contains(gerenteId.valor() + ":" + clienteId.valor());
        }
    }

    private static final class ContasFake implements ContasClientePort {
        private final List<ContaCorrente> contas;
        private int chamadas;

        ContasFake(List<ContaCorrente> contas) {
            this.contas = contas;
        }

        @Override
        public List<ContaCorrente> buscarContasDoCliente(ClienteId clienteId) {
            chamadas++;
            return contas;
        }
    }

    private static final class DadosMestresFake implements DadosMestresClientePort {
        private final Map<ClienteId, DadosMestresCliente> resolvidos;
        private int chamadas;

        DadosMestresFake() {
            this(Map.of(CLIENTE_1, new DadosMestresCliente("ANA BEATRIZ SOUZA", "***.222.333-**")));
        }

        DadosMestresFake(Map<ClienteId, DadosMestresCliente> resolvidos) {
            this.resolvidos = resolvidos;
        }

        @Override
        public Map<ClienteId, DadosMestresCliente> buscarDadosMestres(List<ClienteId> clienteIds) {
            chamadas++;
            return resolvidos;
        }
    }
}
