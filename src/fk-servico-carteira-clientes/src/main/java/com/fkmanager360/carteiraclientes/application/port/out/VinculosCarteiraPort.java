package com.fkmanager360.carteiraclientes.application.port.out;

import com.fkmanager360.carteiraclientes.domain.ClienteId;
import com.fkmanager360.carteiraclientes.domain.GerenteId;
import com.fkmanager360.carteiraclientes.domain.PageResult;
import com.fkmanager360.carteiraclientes.domain.Pagination;

/**
 * Porta de saida para a associacao persistida GerenteRelacionamento &lt;-&gt; Cliente. A
 * aplicacao orquestra atraves desta porta e nao conhece o adapter que a implementa (ADR-0020).
 */
public interface VinculosCarteiraPort {

    PageResult<ClienteId> findPage(GerenteId gerenteId, Pagination pagination);
}
