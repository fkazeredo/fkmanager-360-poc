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

    /**
     * O direito de atendimento <b>atual</b> daquele gerente sobre aquele Cliente (ADR-0007).
     *
     * <p>E consulta local e barata de proposito: e ela que precede toda chamada ao CoreLegado, em
     * toda consulta por conta. Sem direito, a resposta e 403 e nenhuma consulta externa acontece
     * -- ordem normativa, nao otimizacao.
     */
    boolean existeVinculo(GerenteId gerenteId, ClienteId clienteId);
}
