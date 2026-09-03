package com.fkmanager360.credito.domain;

/**
 * Meio pelo qual o Cliente manifestou o pedido ao GerenteRelacionamento (CONTEXT.md de Credito).
 * Fechado por enum deliberadamente -- ao contrario de OrigemSolicitacao, a spec nao anuncia
 * crescimento futuro para este conjunto.
 */
public enum CanalManifestacao {
    PRESENCIAL,
    TELEFONE,
    CANAL_DIGITAL
}
