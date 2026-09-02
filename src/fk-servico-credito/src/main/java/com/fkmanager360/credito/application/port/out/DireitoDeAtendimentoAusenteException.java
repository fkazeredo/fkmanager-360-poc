package com.fkmanager360.credito.application.port.out;

/**
 * CarteiraClientes recusou o atendimento: o ator nao tem direito <b>atual</b> sobre aquele
 * Cliente, ou a conta nao e dele. A borda traduz para 403.
 *
 * <p>Quando isto acontece, nenhuma consulta ao CoreLegado pode ter sido emitida (AC23) -- a
 * ordem esta no caso de uso, nao aqui, mas e esta excecao que a interrompe.
 */
public class DireitoDeAtendimentoAusenteException extends RuntimeException {

    public DireitoDeAtendimentoAusenteException(String message) {
        super(message);
    }
}
