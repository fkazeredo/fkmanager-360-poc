package com.fkmanager360.carteiraclientes.adapter.in.web;

import com.fkmanager360.carteiraclientes.domain.ClienteDaCarteira;

record ClienteResumoResponse(String clienteId, String nome, String cpfMascarado) {

    static ClienteResumoResponse de(ClienteDaCarteira clienteDaCarteira) {
        return new ClienteResumoResponse(
                clienteDaCarteira.clienteId().valor(),
                clienteDaCarteira.dadosMestres().nome(),
                clienteDaCarteira.dadosMestres().cpfMascarado());
    }
}
