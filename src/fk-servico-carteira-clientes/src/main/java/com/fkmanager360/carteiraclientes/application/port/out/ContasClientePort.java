package com.fkmanager360.carteiraclientes.application.port.out;

import com.fkmanager360.carteiraclientes.domain.ClienteId;
import com.fkmanager360.carteiraclientes.domain.ContaCorrente;

import java.util.List;

/**
 * Porta de saida para a ACL propria deste contexto sobre o CoreLegado (ADR-0004), na fatia que
 * lhe diz respeito: identificacao das contas correntes de um Cliente. Nenhum dado financeiro
 * atravessa esta porta.
 *
 * <p>A unica forma de consulta e por {@link ClienteId}, e isso e desenho, nao limitacao: o
 * {@code ClienteId} ja passou pela verificacao de direito de atendimento antes de chegar aqui.
 * Uma consulta por conta permitiria descobrir o dono de uma conta arbitraria sem autorizacao
 * previa, invertendo a ordem que ADR-0007 fixa.
 *
 * <p>Lista vazia significa "este Cliente nao tem conta no Core". Falhas de transporte ou de
 * contrato sao sinalizadas pelas excecoes tipadas desta porta, nunca por um {@code COD-RET}
 * vazando para fora da ACL (ADR-0005).
 */
public interface ContasClientePort {

    List<ContaCorrente> buscarContasDoCliente(ClienteId clienteId);
}
