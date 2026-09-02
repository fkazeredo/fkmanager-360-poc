package com.fkmanager360.simuladorcorelegado.cliente;

/**
 * Resultado por ocorrencia do lote. {@code codRet}/{@code msgRet} sao proprios do item: um lote
 * de 10 pode ter 9 sucessos e 1 "104 CLIENTE NAO ENCONTRADO" no mesmo HTTP 200 -- o status HTTP
 * nao carrega o resultado de negocio por item (ADR-0005). Campos mestres vem em branco quando
 * {@code codRet} nao e sucesso.
 */
public record ClienteLegadoItemResposta(
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

    static ClienteLegadoItemResposta sucesso(RegistroClienteLegado registro) {
        return new ClienteLegadoItemResposta(
                registro.codCli(), SUCESSO, "OPERACAO CONCLUIDA COM SUCESSO",
                registro.nomCli(), registro.numCpf(), registro.sitCad(), registro.datCad());
    }

    static ClienteLegadoItemResposta naoEncontrado(String codCli) {
        return new ClienteLegadoItemResposta(
                codCli, CLIENTE_NAO_ENCONTRADO, "CLIENTE NAO ENCONTRADO",
                "", "", "", "");
    }
}
