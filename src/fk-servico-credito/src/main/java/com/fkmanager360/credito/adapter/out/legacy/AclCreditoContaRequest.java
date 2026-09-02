package com.fkmanager360.credito.adapter.out.legacy;

/**
 * Copia propria desta ACL do payload host-centric, e nao um tipo compartilhado com
 * simulador-core-legado (ADR-0011). O acoplamento aqui e por contrato HTTP, nao por classe.
 */
record AclCreditoContaRequest(String numCta) {
}
