package com.fkmanager360.carteiraclientes.adapter.in.web;

import com.fkmanager360.carteiraclientes.domain.ClienteDaCarteira;
import io.swagger.v3.oas.annotations.media.Schema;

record ClienteResumoResponse(
        @Schema(example = "1", requiredMode = Schema.RequiredMode.REQUIRED) String clienteId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String nome,
        @Schema(example = "***.456.789-**", requiredMode = Schema.RequiredMode.REQUIRED) String cpfMascarado) {

    static ClienteResumoResponse de(ClienteDaCarteira clienteDaCarteira) {
        return new ClienteResumoResponse(
                clienteDaCarteira.clienteId().valor(),
                clienteDaCarteira.dadosMestres().nome(),
                clienteDaCarteira.dadosMestres().cpfMascarado());
    }
}
