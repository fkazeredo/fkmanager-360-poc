package com.fkmanager360.carteiraclientes.adapter.out.legacy;

import java.util.List;

record AclBatchQueryResponse(String codRet, String msgRet, List<AclResponseItem> clientes) {

    record AclResponseItem(
            String codCli, String codRet, String msgRet,
            String nomCli, String numCpf, String sitCad, String datCad) {
    }
}
