package com.fkmanager360.credito.domain;

/**
 * A situacao da conta como Credito precisa dela: regular, ou nao. A gradacao do host (bloqueada,
 * encerrada, e o que mais o legado distinguir) fica na ACL -- para a PoliticaCredito, quando ela
 * existir, a pergunta e binaria, e o status host bruto nunca e apresentado (ADR-0005).
 */
public enum SituacaoConta {
    REGULAR,
    IRREGULAR
}
