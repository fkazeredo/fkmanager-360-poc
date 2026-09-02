package com.fkmanager360.carteiraclientes.aplicacao;

import com.fkmanager360.carteiraclientes.aplicacao.portas.PortaDadosMestresCliente;
import com.fkmanager360.carteiraclientes.aplicacao.portas.PortaVinculosCarteira;
import com.fkmanager360.carteiraclientes.dominio.ClienteDaCarteira;
import com.fkmanager360.carteiraclientes.dominio.ClienteId;
import com.fkmanager360.carteiraclientes.dominio.DadosMestresCliente;
import com.fkmanager360.carteiraclientes.dominio.GerenteId;
import com.fkmanager360.carteiraclientes.dominio.PaginaResultado;
import com.fkmanager360.carteiraclientes.dominio.Paginacao;

/**
 * Caso de uso: o gerente autenticado ve a lista paginada dos Clientes da sua CarteiraClientes
 * (AC22). Orquestra duas portas -- a associacao persistida e a ACL do CoreLegado -- sem conhecer
 * qual adapter implementa cada uma (ADR-0020).
 */
public class ListarClientesDaCarteira {

    private final PortaVinculosCarteira vinculos;
    private final PortaDadosMestresCliente dadosMestres;

    public ListarClientesDaCarteira(PortaVinculosCarteira vinculos, PortaDadosMestresCliente dadosMestres) {
        this.vinculos = vinculos;
        this.dadosMestres = dadosMestres;
    }

    public PaginaResultado<ClienteDaCarteira> executar(GerenteId gerenteId, Paginacao paginacao) {
        PaginaResultado<ClienteId> pagina = vinculos.buscarPagina(gerenteId, paginacao);

        if (pagina.itens().isEmpty()) {
            return pagina.mapear(id -> new ClienteDaCarteira(id, DadosMestresCliente.indisponivel()));
        }

        var resolvidos = dadosMestres.buscarDadosMestres(pagina.itens());

        return pagina.mapear(id -> new ClienteDaCarteira(
                id, resolvidos.getOrDefault(id, DadosMestresCliente.indisponivel())));
    }
}
