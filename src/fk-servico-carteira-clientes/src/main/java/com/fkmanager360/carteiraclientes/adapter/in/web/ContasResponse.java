package com.fkmanager360.carteiraclientes.adapter.in.web;

import com.fkmanager360.carteiraclientes.domain.ContaCorrente;

import java.util.List;

/**
 * As contas de um Cliente. Sem paginacao: a quantidade de contas de um Cliente e naturalmente
 * pequena, e paginar aqui seria infraestrutura sem necessidade (ADR-0010).
 */
record ContasResponse(List<ContaResumoResponse> itens) {

    static ContasResponse de(List<ContaCorrente> contas) {
        return new ContasResponse(contas.stream().map(ContaResumoResponse::de).toList());
    }
}
