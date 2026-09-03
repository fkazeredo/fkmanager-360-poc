package com.fkmanager360.credito.domain;

/**
 * A versao de PoliticaCredito exigida nao possui implementacao registrada no MotorDecisaoCredito.
 * Lancada em dois momentos, ambos deliberados: na construcao do motor, se a propria
 * {@code versaoVigente} nao estiver entre as conhecidas; e em {@code decidir(...)}, se o
 * ContextoDecisaoCredito referenciar uma versao que o motor nao conhece. Em nenhum dos dois casos
 * o motor cai silenciosamente em outra versao.
 */
public class VersaoPoliticaCreditoIndisponivelException extends RuntimeException {

    public VersaoPoliticaCreditoIndisponivelException(String message) {
        super(message);
    }
}
