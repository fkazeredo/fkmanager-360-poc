package com.fkmanager360.carteiraclientes.application.usecase;

import com.fkmanager360.carteiraclientes.application.port.out.ContasClientePort;
import com.fkmanager360.carteiraclientes.application.port.out.VinculosCarteiraPort;
import com.fkmanager360.carteiraclientes.domain.ClienteId;
import com.fkmanager360.carteiraclientes.domain.ContaCorrente;
import com.fkmanager360.carteiraclientes.domain.ContaId;
import com.fkmanager360.carteiraclientes.domain.GerenteId;

/**
 * O primitivo de autorizacao deste contexto: confirma que o gerente tem direito de atendimento
 * atual sobre o Cliente, e que a conta pedida pertence de fato aquele Cliente segundo o
 * CoreLegado (AC23, ADR-0007).
 *
 * <p>Devolve apenas a {@link ContaCorrente} confirmada -- identificacao, nada cadastral. Este e o
 * caso de uso que qualquer consumidor que precise apenas de "este atendimento e legitimo?" deve
 * usar, sem pagar o custo (nem o acoplamento de disponibilidade) da consulta de dados mestres do
 * Cliente. {@link ConsultarContextoAtendimento} compoe este primitivo com
 * {@link com.fkmanager360.carteiraclientes.application.port.out.DadosMestresClientePort} apenas
 * quando a apresentacao de fato precisa do nome e do CPF.
 *
 * <p>A ordem das duas verificacoes e normativa: o vinculo local precede qualquer chamada ao
 * CoreLegado (AC23). O {@code clienteId} recebido nao e verdade sobre a quem a conta pertence --
 * e apenas a chave que autoriza a consulta; quem afirma a pertinencia da conta e o CoreLegado.
 */
public class ConfirmarDireitoDeAtendimento {

    private final VinculosCarteiraPort vinculos;
    private final ContasClientePort contas;

    public ConfirmarDireitoDeAtendimento(VinculosCarteiraPort vinculos, ContasClientePort contas) {
        this.vinculos = vinculos;
        this.contas = contas;
    }

    public ContaCorrente executar(GerenteId gerenteId, ClienteId clienteId, ContaId contaId) {
        if (!vinculos.existeVinculo(gerenteId, clienteId)) {
            throw new DireitoDeAtendimentoAusenteException(
                    "Sem direito de atendimento atual sobre o Cliente " + clienteId.valor());
        }

        return contas.buscarContasDoCliente(clienteId).stream()
                .filter(candidata -> candidata.contaId().equals(contaId))
                .findFirst()
                .orElseThrow(() -> new ContaNaoEncontradaException(
                        "A conta " + contaId.valor() + " nao pertence ao Cliente " + clienteId.valor()));
    }
}
