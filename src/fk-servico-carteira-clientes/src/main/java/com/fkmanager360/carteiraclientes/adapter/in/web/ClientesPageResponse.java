package com.fkmanager360.carteiraclientes.adapter.in.web;

import com.fkmanager360.carteiraclientes.domain.ClienteDaCarteira;
import com.fkmanager360.carteiraclientes.domain.PageResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

// Nomes de campo (itens/pagina/tamanho/totalElementos/totalPaginas) sao o contrato JSON publico
// ja exercitado por Angular/Playwright -- nao mudam so porque PageResult, internamente, agora usa
// nomes tecnicos em ingles (items/page/size/totalElements/totalPages).
record ClientesPageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ClienteResumoResponse> itens,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int pagina,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int tamanho,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long totalElementos,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long totalPaginas) {

    static ClientesPageResponse de(PageResult<ClienteDaCarteira> pageResult) {
        return new ClientesPageResponse(
                pageResult.items().stream().map(ClienteResumoResponse::de).toList(),
                pageResult.page(), pageResult.size(), pageResult.totalElements(), pageResult.totalPages());
    }
}
