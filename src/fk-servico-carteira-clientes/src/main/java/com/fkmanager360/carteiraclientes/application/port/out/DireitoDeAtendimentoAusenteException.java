package com.fkmanager360.carteiraclientes.application.port.out;

/**
 * O ator autenticado nao tem direito de atendimento <b>atual</b> sobre aquele Cliente. A borda
 * traduz para 403 (ADR-0007): a recusa e produzida pelo backend dono da associacao, e vale
 * mesmo quando a requisicao chega sem passar pelas restricoes de navegacao do app-gerente.
 */
public class DireitoDeAtendimentoAusenteException extends RuntimeException {

    public DireitoDeAtendimentoAusenteException(String message) {
        super(message);
    }
}
