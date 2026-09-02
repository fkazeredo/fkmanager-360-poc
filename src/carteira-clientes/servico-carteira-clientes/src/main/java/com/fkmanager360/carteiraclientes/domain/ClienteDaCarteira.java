package com.fkmanager360.carteiraclientes.domain;

/**
 * Um item da listagem: a identidade do vinculo composta com os dados mestres do Cliente. Modelo
 * de apresentacao deste contexto -- nao um agregado.
 */
public record ClienteDaCarteira(ClienteId clienteId, DadosMestresCliente dadosMestres) {
}
