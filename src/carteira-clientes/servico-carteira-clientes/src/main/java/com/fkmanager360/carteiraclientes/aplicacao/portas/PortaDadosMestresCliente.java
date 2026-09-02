package com.fkmanager360.carteiraclientes.aplicacao.portas;

import com.fkmanager360.carteiraclientes.dominio.ClienteId;
import com.fkmanager360.carteiraclientes.dominio.DadosMestresCliente;

import java.util.List;
import java.util.Map;

/**
 * Porta de saida para a ACL propria deste contexto sobre o CoreLegado (ADR-0004). Consulta em
 * lote, para que a listagem paginada nao dispare uma chamada por Cliente da pagina.
 *
 * <p>O mapa devolvido contem apenas os {@link ClienteId} resolvidos com sucesso; um
 * {@code ClienteId} ausente do resultado significa "nao encontrado no Core" -- o chamador decide
 * o que fazer com essa ausencia. Falhas de transporte ou de contrato (indisponibilidade, timeout,
 * resposta invalida) sao sinalizadas pelas excecoes desta mesma porta, nunca por um {@code
 * COD-RET} vazando para fora da ACL (ADR-0005).
 */
public interface PortaDadosMestresCliente {

    Map<ClienteId, DadosMestresCliente> buscarDadosMestres(List<ClienteId> clienteIds);
}
