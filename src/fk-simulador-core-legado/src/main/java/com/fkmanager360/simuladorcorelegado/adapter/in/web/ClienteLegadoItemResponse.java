package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import com.fkmanager360.simuladorcorelegado.domain.ClienteLegadoRecord;

/**
 * Resultado por ocorrencia do lote. {@code codRet}/{@code msgRet} sao proprios do item: um lote
 * de 10 pode ter 9 sucessos e 1 "104 CLIENTE NAO ENCONTRADO" no mesmo HTTP 200 -- o status HTTP
 * nao carrega o resultado de negocio por item (ADR-0005). Campos mestres vem em branco quando
 * {@code codRet} nao e sucesso.
 */
public record ClienteLegadoItemResponse(
        String codCli,
        String codRet,
        String msgRet,
        String nomCli,
        String numCpf,
        String sitCad,
        String datCad
) {

    public static final String SUCESSO = "000";
    public static final String CLIENTE_NAO_ENCONTRADO = "104";

    static ClienteLegadoItemResponse sucesso(ClienteLegadoRecord registro) {
        return new ClienteLegadoItemResponse(
                registro.codCli(), SUCESSO, "OPERACAO CONCLUIDA COM SUCESSO",
                registro.nomCli(), registro.numCpf(), registro.sitCad(), registro.datCad());
    }

    static ClienteLegadoItemResponse naoEncontrado(String codCli) {
        return new ClienteLegadoItemResponse(
                codCli, CLIENTE_NAO_ENCONTRADO, "CLIENTE NAO ENCONTRADO",
                "", "", "", "");
    }
}
