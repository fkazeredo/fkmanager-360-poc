package com.fkmanager360.carteiraclientes.aplicacao.portas;

import com.fkmanager360.carteiraclientes.dominio.ClienteId;
import com.fkmanager360.carteiraclientes.dominio.GerenteId;
import com.fkmanager360.carteiraclientes.dominio.PaginaResultado;
import com.fkmanager360.carteiraclientes.dominio.Paginacao;

/**
 * Porta de saida para a associacao persistida GerenteRelacionamento &lt;-&gt; Cliente. A
 * aplicacao orquestra atraves desta porta e nao conhece o adapter que a implementa (ADR-0020).
 */
public interface PortaVinculosCarteira {

    PaginaResultado<ClienteId> buscarPagina(GerenteId gerenteId, Paginacao paginacao);
}
