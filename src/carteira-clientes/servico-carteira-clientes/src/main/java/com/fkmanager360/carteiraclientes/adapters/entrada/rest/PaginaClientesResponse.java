package com.fkmanager360.carteiraclientes.adapters.entrada.rest;

import com.fkmanager360.carteiraclientes.dominio.ClienteDaCarteira;
import com.fkmanager360.carteiraclientes.dominio.PaginaResultado;

import java.util.List;

record PaginaClientesResponse(
        List<ClienteResumoResponse> itens, int pagina, int tamanho, long totalElementos, long totalPaginas) {

    static PaginaClientesResponse de(PaginaResultado<ClienteDaCarteira> pagina) {
        return new PaginaClientesResponse(
                pagina.itens().stream().map(ClienteResumoResponse::de).toList(),
                pagina.pagina(), pagina.tamanho(), pagina.totalElementos(), pagina.totalPaginas());
    }
}
