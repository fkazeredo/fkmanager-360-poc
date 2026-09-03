package com.fkmanager360.credito.domain;

/**
 * Quem originou a SolicitacaoAumentoLimite. Hoje possui um unico valor -- CLIENTE --, estabelecido
 * pelo dominio e nunca aceito do cliente HTTP (CONTEXT.md de Credito, User Story 16). Modelado
 * como enum de um so valor deliberadamente: um enum admite trivialmente um novo valor no futuro
 * sem que nenhum codigo deste ticket precise mudar, e a unica forma publica de obter uma instancia
 * continua sendo a constante {@link #CLIENTE} -- nunca um valor vindo de fora.
 */
public enum OrigemSolicitacao {
    CLIENTE
}
