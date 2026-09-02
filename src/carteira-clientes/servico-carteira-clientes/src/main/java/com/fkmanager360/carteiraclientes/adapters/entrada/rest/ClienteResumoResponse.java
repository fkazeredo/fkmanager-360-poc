package com.fkmanager360.carteiraclientes.adapters.entrada.rest;

import com.fkmanager360.carteiraclientes.dominio.ClienteDaCarteira;

record ClienteResumoResponse(String clienteId, String nome, String cpfMascarado) {

    static ClienteResumoResponse de(ClienteDaCarteira clienteDaCarteira) {
        return new ClienteResumoResponse(
                clienteDaCarteira.clienteId().valor(),
                clienteDaCarteira.dadosMestres().nome(),
                clienteDaCarteira.dadosMestres().cpfMascarado());
    }
}
