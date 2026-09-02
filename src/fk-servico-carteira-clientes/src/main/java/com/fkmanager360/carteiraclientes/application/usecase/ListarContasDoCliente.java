package com.fkmanager360.carteiraclientes.application.usecase;

import com.fkmanager360.carteiraclientes.application.port.out.ContasClientePort;
import com.fkmanager360.carteiraclientes.application.port.out.VinculosCarteiraPort;
import com.fkmanager360.carteiraclientes.domain.ClienteId;
import com.fkmanager360.carteiraclientes.domain.ContaCorrente;
import com.fkmanager360.carteiraclientes.domain.GerenteId;

import java.util.List;

/**
 * Caso de uso: selecionar um Cliente e ver suas ContaCorrentes (AC22).
 *
 * <p>A ordem das duas linhas abaixo e normativa, nao estilistica: o direito de atendimento
 * <b>atual</b> e verificado na persistencia local <b>antes</b> de qualquer chamada ao CoreLegado
 * (ADR-0007). Sem direito, nenhuma consulta externa acontece -- e o mesmo invariante que o AC23
 * exige da consulta de limite.
 */
public class ListarContasDoCliente {

    private final VinculosCarteiraPort vinculos;
    private final ContasClientePort contas;

    public ListarContasDoCliente(VinculosCarteiraPort vinculos, ContasClientePort contas) {
        this.vinculos = vinculos;
        this.contas = contas;
    }

    public List<ContaCorrente> executar(GerenteId gerenteId, ClienteId clienteId) {
        if (!vinculos.existeVinculo(gerenteId, clienteId)) {
            throw new DireitoDeAtendimentoAusenteException(
                    "Sem direito de atendimento atual sobre o Cliente " + clienteId.valor());
        }

        return contas.buscarContasDoCliente(clienteId);
    }
}
