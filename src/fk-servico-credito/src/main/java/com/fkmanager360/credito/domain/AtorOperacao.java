package com.fkmanager360.credito.domain;

/**
 * Quem responde por uma operacao registrada pelo sistema: sempre um {@link AtorHumano} ou um
 * {@link AtorSistema}, nunca ausente (CONTEXT.md raiz, secao "Atores"). Autoria de negocio e
 * execucao tecnica nao se confundem -- uma DecisaoCredito tomada por um ator continua sendo dele
 * mesmo quando outro mecanismo executa o passo seguinte.
 */
public sealed interface AtorOperacao permits AtorHumano, AtorSistema {
}
