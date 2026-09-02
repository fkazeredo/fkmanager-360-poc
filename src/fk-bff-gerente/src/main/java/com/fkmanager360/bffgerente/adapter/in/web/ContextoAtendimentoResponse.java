package com.fkmanager360.bffgerente.adapter.in.web;

/**
 * O que servico-carteira-clientes devolve. Copia propria da forma do contrato HTTP, e nao um tipo
 * compartilhado: entidades nao atravessam bounded contexts, e o BFF nem sequer e um deles
 * (ADR-0011, CONTEXT-MAP.md).
 */
record ContextoAtendimentoResponse(String clienteId, String nome, String cpfMascarado, ContaResponse conta) {

    record ContaResponse(String contaId, String agencia) {
    }
}
