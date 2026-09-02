package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.ClienteId;
import com.fkmanager360.credito.domain.ContaId;

/**
 * Porta de saida para a verificacao do direito de atendimento em CarteiraClientes, que e a
 * autoridade sobre a associacao atual GerenteRelacionamento - Cliente (ADR-0007). Credito nao
 * reimplementa essa regra nem guarda copia dela: pergunta a quem e dono.
 *
 * <p>Devolver normalmente significa "o atendimento e legitimo"; a recusa vem como
 * {@link DireitoDeAtendimentoAusenteException}. O retorno carrega o {@code clienteId}
 * autoritativo -- e nada cadastral, que nao pertence a este contexto (AC30).
 */
public interface DireitoDeAtendimentoPort {

    ClienteId confirmarDireitoDeAtendimento(ClienteId clienteId, ContaId contaId);
}
