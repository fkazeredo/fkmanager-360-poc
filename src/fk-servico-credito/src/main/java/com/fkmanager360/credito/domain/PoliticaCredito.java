package com.fkmanager360.credito.domain;

/**
 * Conjunto ficticio de regras que classifica uma SolicitacaoAumentoLimite (CONTEXT.md de Credito).
 * E versionada porque toda DecisaoCredito registra sob qual versao foi tomada -- {@link #versao()}
 * e o que {@link MotorDecisaoCredito} usa para indexar o registry de politicas conhecidas.
 *
 * <p>{@link #avaliar(ContextoDecisaoCredito)} e total: para qualquer ContextoDecisaoCredito valido,
 * sempre produz um {@link MotivoDecisaoCredito} -- nunca lanca excecao por um caso "nao coberto".
 */
public interface PoliticaCredito {

    VersaoPoliticaCredito versao();

    MotivoDecisaoCredito avaliar(ContextoDecisaoCredito contexto);
}
