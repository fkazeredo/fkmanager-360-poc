package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.ClienteId;
import com.fkmanager360.credito.domain.ContaId;

/**
 * Porta de saida para a verificacao do direito de atendimento em CarteiraClientes, que e a
 * autoridade sobre a associacao atual GerenteRelacionamento - Cliente (ADR-0007). Credito nao
 * reimplementa essa regra nem guarda copia dela: pergunta a quem e dono.
 *
 * <p>Retorno normal significa "o atendimento e legitimo"; a recusa vem como
 * {@link DireitoDeAtendimentoAusenteException} (sem vinculo) ou {@link ContaNaoEncontradaException}
 * (conta que nao pertence ao Cliente). Nao ha nada a devolver alem disso: {@code clienteId} e
 * {@code contaId} ja sao parametros de entrada, conhecidos por quem chama, e a operacao remota
 * que implementa esta porta nao devolve -- nem precisa devolver -- nenhum dado cadastral do
 * Cliente, que nao pertence a este contexto (AC30).
 */
public interface DireitoDeAtendimentoPort {

    void confirmarDireitoDeAtendimento(ClienteId clienteId, ContaId contaId);
}
