package com.fkmanager360.carteiraclientes.adapter.in.web;

import com.fkmanager360.carteiraclientes.domain.ContaCorrente;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Identificacao da ContaCorrente na borda. Sem limite, sem saldo, sem situacao: este contexto nao
 * e fachada financeira (ADR-0004, AC30).
 */
record ContaResumoResponse(
        @Schema(example = "10001", requiredMode = Schema.RequiredMode.REQUIRED) String contaId,
        @Schema(example = "0001", requiredMode = Schema.RequiredMode.REQUIRED) String agencia) {

    static ContaResumoResponse de(ContaCorrente conta) {
        return new ContaResumoResponse(conta.contaId().valor(), conta.agencia());
    }
}
