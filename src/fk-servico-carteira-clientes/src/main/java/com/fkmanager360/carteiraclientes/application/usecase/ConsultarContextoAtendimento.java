package com.fkmanager360.carteiraclientes.application.usecase;

import com.fkmanager360.carteiraclientes.application.port.out.ContasClientePort;
import com.fkmanager360.carteiraclientes.application.port.out.DadosMestresClientePort;
import com.fkmanager360.carteiraclientes.application.port.out.VinculosCarteiraPort;
import com.fkmanager360.carteiraclientes.domain.ClienteId;
import com.fkmanager360.carteiraclientes.domain.ContaCorrente;
import com.fkmanager360.carteiraclientes.domain.ContaId;
import com.fkmanager360.carteiraclientes.domain.ContextoAtendimento;
import com.fkmanager360.carteiraclientes.domain.DadosMestresCliente;
import com.fkmanager360.carteiraclientes.domain.GerenteId;

import java.util.List;

/**
 * Caso de uso: o contexto de atendimento de uma conta -- quem e o Cliente e qual conta dele esta
 * sendo atendida. Consumido pela composicao de tela do bff-gerente e por servico-credito, que
 * precisa da verificacao de direito antes de ler qualquer coisa no Core (AC23, AC30).
 *
 * <p>A sequencia e o ponto inteiro deste caso de uso:
 *
 * <ol>
 *   <li>o direito de atendimento atual sobre o <b>Cliente</b> e verificado na persistencia local;
 *       sem ele, 403 e <b>zero</b> chamadas ao CoreLegado;</li>
 *   <li>so entao o Core e consultado, e sempre pela chave ja autorizada -- "quais contas sao
 *       deste Cliente";</li>
 *   <li>a pertinencia da conta e confirmada contra essa resposta autoritativa.</li>
 * </ol>
 *
 * <p>O {@code clienteId} recebido nao e tratado como verdade: ele e apenas a chave que torna
 * possivel a verificacao barata antes do Core. Quem afirma que a conta pertence aquele Cliente e
 * o CoreLegado (ADR-0002), nunca o payload de quem chamou -- por isso uma conta que exista, mas
 * nao esteja entre as daquele Cliente, nao produz contexto algum.
 */
public class ConsultarContextoAtendimento {

    private final VinculosCarteiraPort vinculos;
    private final ContasClientePort contas;
    private final DadosMestresClientePort dadosMestres;

    public ConsultarContextoAtendimento(
            VinculosCarteiraPort vinculos, ContasClientePort contas, DadosMestresClientePort dadosMestres) {
        this.vinculos = vinculos;
        this.contas = contas;
        this.dadosMestres = dadosMestres;
    }

    public ContextoAtendimento executar(GerenteId gerenteId, ClienteId clienteId, ContaId contaId) {
        if (!vinculos.existeVinculo(gerenteId, clienteId)) {
            throw new DireitoDeAtendimentoAusenteException(
                    "Sem direito de atendimento atual sobre o Cliente " + clienteId.valor());
        }

        ContaCorrente conta = contas.buscarContasDoCliente(clienteId).stream()
                .filter(candidata -> candidata.contaId().equals(contaId))
                .findFirst()
                .orElseThrow(() -> new ContaNaoEncontradaException(
                        "A conta " + contaId.valor() + " nao pertence ao Cliente " + clienteId.valor()));

        DadosMestresCliente dados = dadosMestres.buscarDadosMestres(List.of(clienteId))
                .getOrDefault(clienteId, DadosMestresCliente.indisponivel());

        return new ContextoAtendimento(clienteId, dados, conta);
    }
}
