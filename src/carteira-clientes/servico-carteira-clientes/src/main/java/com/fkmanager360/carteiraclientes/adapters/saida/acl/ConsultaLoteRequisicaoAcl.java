package com.fkmanager360.carteiraclientes.adapters.saida.acl;

import java.util.List;

/**
 * Copia propria desta ACL do payload host-centric, e nao um tipo compartilhado com
 * simulador-core-legado (ADR-0011: nenhuma entidade de dominio Java atravessa bounded contexts).
 * O acoplamento aqui e por contrato HTTP, nao por classe.
 */
record ConsultaLoteRequisicaoAcl(List<String> codCli) {
}
