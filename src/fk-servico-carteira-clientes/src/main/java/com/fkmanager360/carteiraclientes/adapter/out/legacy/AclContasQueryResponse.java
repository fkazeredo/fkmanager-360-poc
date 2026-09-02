package com.fkmanager360.carteiraclientes.adapter.out.legacy;

import java.util.List;

record AclContasQueryResponse(String codRet, String msgRet, String codCli, List<AclContaItem> contas) {

    record AclContaItem(String numCta, String codAge) {
    }
}
