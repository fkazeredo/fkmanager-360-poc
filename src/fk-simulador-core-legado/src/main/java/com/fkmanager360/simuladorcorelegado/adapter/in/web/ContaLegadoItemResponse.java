package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import com.fkmanager360.simuladorcorelegado.domain.ContaLegadoRecord;

/**
 * Ocorrencia da consulta de contas. Carrega apenas a identificacao da conta: situacao, limite e
 * risco pertencem a consulta de credito, que e outra capacidade e outro consumidor.
 */
public record ContaLegadoItemResponse(String numCta, String codAge) {

    static ContaLegadoItemResponse de(ContaLegadoRecord registro) {
        return new ContaLegadoItemResponse(registro.numCta(), registro.codAge());
    }
}
