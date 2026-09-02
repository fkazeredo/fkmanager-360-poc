package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import java.util.List;

/**
 * Envelope da consulta de contas. Como em todo este contrato, o HTTP 200 nao carrega o resultado
 * de negocio (ADR-0005): "nenhuma ocorrencia" chega como {@code codRet} "121", nao como 404.
 */
public record ContasLegadoQueryResponse(
        String codRet,
        String msgRet,
        String codCli,
        List<ContaLegadoItemResponse> contas
) {

    static final String SUCESSO = "000";
    static final String CONTA_NAO_ENCONTRADA = "121";

    static ContasLegadoQueryResponse encontradas(String codCli, List<ContaLegadoItemResponse> contas) {
        return new ContasLegadoQueryResponse(SUCESSO, "OPERACAO CONCLUIDA COM SUCESSO", codCli, contas);
    }

    static ContasLegadoQueryResponse nenhumaOcorrencia(String codCli) {
        return new ContasLegadoQueryResponse(CONTA_NAO_ENCONTRADA, "CONTA NAO ENCONTRADA", codCli, List.of());
    }
}
