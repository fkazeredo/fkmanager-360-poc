package com.fkmanager360.carteiraclientes.application.usecase;

import com.fkmanager360.carteiraclientes.application.port.out.DadosMestresClientePort;
import com.fkmanager360.carteiraclientes.application.port.out.VinculosCarteiraPort;
import com.fkmanager360.carteiraclientes.domain.ClienteDaCarteira;
import com.fkmanager360.carteiraclientes.domain.ClienteId;
import com.fkmanager360.carteiraclientes.domain.DadosMestresCliente;
import com.fkmanager360.carteiraclientes.domain.GerenteId;
import com.fkmanager360.carteiraclientes.domain.PageResult;
import com.fkmanager360.carteiraclientes.domain.Pagination;

/**
 * Caso de uso: o gerente autenticado ve a lista paginada dos Clientes da sua CarteiraClientes
 * (AC22). Orquestra duas portas -- a associacao persistida e a ACL do CoreLegado -- sem conhecer
 * qual adapter implementa cada uma (ADR-0020).
 */
public class ListarClientesDaCarteira {

    private final VinculosCarteiraPort vinculos;
    private final DadosMestresClientePort dadosMestres;

    public ListarClientesDaCarteira(VinculosCarteiraPort vinculos, DadosMestresClientePort dadosMestres) {
        this.vinculos = vinculos;
        this.dadosMestres = dadosMestres;
    }

    public PageResult<ClienteDaCarteira> executar(GerenteId gerenteId, Pagination pagination) {
        PageResult<ClienteId> page = vinculos.findPage(gerenteId, pagination);

        if (page.items().isEmpty()) {
            return page.map(id -> new ClienteDaCarteira(id, DadosMestresCliente.indisponivel()));
        }

        var resolvidos = dadosMestres.buscarDadosMestres(page.items());

        return page.map(id -> new ClienteDaCarteira(
                id, resolvidos.getOrDefault(id, DadosMestresCliente.indisponivel())));
    }
}
