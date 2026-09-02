package com.fkmanager360.carteiraclientes.adapter.in.web;

import com.fkmanager360.carteiraclientes.domain.ContaCorrente;

/**
 * Identificacao da ContaCorrente na borda. Sem limite, sem saldo, sem situacao: este contexto nao
 * e fachada financeira (ADR-0004, AC30).
 */
record ContaResumoResponse(String contaId, String agencia) {

    static ContaResumoResponse de(ContaCorrente conta) {
        return new ContaResumoResponse(conta.contaId().valor(), conta.agencia());
    }
}
