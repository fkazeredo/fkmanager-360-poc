package com.fkmanager360.carteiraclientes.adapters.saida.acl;

import java.util.List;

record ConsultaLoteRespostaAcl(String codRet, String msgRet, List<ItemRespostaAcl> clientes) {

    record ItemRespostaAcl(
            String codCli, String codRet, String msgRet,
            String nomCli, String numCpf, String sitCad, String datCad) {
    }
}
