package com.fkmanager360.simuladorcorelegado.cliente;

import java.util.List;

/**
 * Envelope do lote. {@code codRet}/{@code msgRet} no nivel do lote referem-se ao processamento da
 * requisicao como um todo (sempre "000" quando a requisicao chega bem formada ate aqui -- as
 * falhas estruturais sao recusadas antes, por HTTP 400); o resultado por Cliente esta em
 * {@code clientes}.
 */
public record ClientesLegadoQueryResponse(
        String codRet,
        String msgRet,
        List<ClienteLegadoItemResponse> clientes
) {

    static ClientesLegadoQueryResponse processado(List<ClienteLegadoItemResponse> clientes) {
        return new ClientesLegadoQueryResponse("000", "LOTE PROCESSADO", clientes);
    }
}
