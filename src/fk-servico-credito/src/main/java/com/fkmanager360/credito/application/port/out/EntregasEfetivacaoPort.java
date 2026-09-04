package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.ProtocoloCore;
import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;

import java.time.Duration;
import java.time.Instant;

/**
 * Porta de saida do dispatcher de efetivacao sobre o metadado de entrega (plano #0004, secoes 1, 4
 * e 9). O adapter que implementa esta porta e puramente mecanico -- nao decide retry nem
 * classifica resultado, apenas persiste o que a aplicacao ja decidiu, com fencing por
 * {@code claimId} em toda escrita.
 *
 * <p><b>Claim unitario (decisao do Owner, OD-1):</b> {@link #reclamarProxima} reclama NO MAXIMO uma
 * entrega por chamada -- nunca uma colecao. O "lote" de {@code credito.efetivacao.entrega.lote}
 * pertence ao loop de episodios por tick na camada de agendamento, nao a esta porta.
 *
 * <p><b>Conclusao definitiva nao mora aqui</b> (revisao do Owner, 2026-09-04): o desfecho que
 * conclui a solicitacao e orquestrado por {@code RegistrarResultadoEfetivacao} dentro de uma
 * {@link TransacaoPort}, compondo {@link #claimAindaValido} + {@link ResultadoEfetivacaoPort} +
 * {@link #terminalizarPorFalhaDefinitiva} -- esta porta contribui as duas operacoes de claim, mas
 * a regra de composicao e da aplicacao.
 */
public interface EntregasEfetivacaoPort {

    /**
     * TX-A: reclama atomicamente, sob {@code FOR UPDATE SKIP LOCKED}, a proxima entrega
     * {@code PENDENTE} devida (claim livre ou expirado). Se a entrega encontrada ja tiver
     * {@code tentativas >= maxTentativas}, termina-a tecnicamente como {@code ESGOTADA} dentro do
     * MESMO lock, sem iniciar novo episodio HTTP -- cobre tanto o esgotamento normal quanto o
     * crash entre o commit do claim e o envio da ultima tentativa reservada.
     */
    ReclamacaoEntrega reclamarProxima(Instant agora, int maxTentativas, Duration lease);

    /** TX-B (aceite): persiste o {@link ProtocoloCore} e fecha a entrega como {@code ACEITA}. */
    ResultadoRegistroEntrega registrarAceite(EntregaEfetivacaoReclamada claim, ProtocoloCore protocoloCore, Instant agora);

    /**
     * TX-B (transitorio): libera o claim e reagenda para {@code proximaTentativaEm} -- a entrega
     * volta a {@code PENDENTE} imediatamente, sem esperar o lease expirar (lifecycle do claim,
     * secao 1 do plano).
     */
    ResultadoRegistroEntrega reagendar(
            EntregaEfetivacaoReclamada claim, Instant proximaTentativaEm, String erroSanitizado, Instant agora);

    /** TX-B (indeterminado): fecha a entrega como {@code INDETERMINADA}. Nunca conclui a solicitacao (OD-3). */
    ResultadoRegistroEntrega marcarIndeterminada(EntregaEfetivacaoReclamada claim, String erroSanitizado, Instant agora);

    /**
     * Fencing sob lock fresco ({@code SELECT ... FOR UPDATE}): true somente se a entrega ainda
     * esta {@code PENDENTE} e o {@code claimId} apresentado ainda e o corrente. O lock adquirido
     * persiste ate o fim da transacao corrente -- e ele que serializa dois workers disputando o
     * mesmo desfecho. <b>So pode ser chamado dentro de uma {@link TransacaoPort} ativa</b>
     * (propagacao {@code MANDATORY} no adapter): fora dela o lock evaporaria no autocommit e o
     * fencing seria ilusorio.
     */
    boolean claimAindaValido(EntregaEfetivacaoReclamada claim);

    /**
     * Fecha a entrega como {@code FALHA_DEFINITIVA} (classe {@code DEFINITIVO}), zerando claim e
     * agenda. Mesma exigencia de transacao ativa de {@link #claimAindaValido} -- e chamada depois
     * dele, na mesma unidade, pela composicao de {@code RegistrarResultadoEfetivacao}.
     */
    void terminalizarPorFalhaDefinitiva(EntregaEfetivacaoReclamada claim, Instant agora);

    /**
     * Fecha a entrega quando outro caminho (callback, #0005) ja terminalizou a solicitacao antes
     * desta chamada sob claim aplicar a falha definitiva que o dispatcher trazia -- o terminal
     * PERSISTIDO e autoritativo, e dita como a entrega termina tecnicamente, nunca o resultado
     * perdedor do dispatcher: {@code EFETIVADA} vira {@code ACEITA} (a instrucao efetivamente foi
     * aceita e concluida, so que por outro caminho); {@code FALHA_EFETIVACAO} vira
     * {@code FALHA_DEFINITIVA}. Mesma exigencia de transacao ativa de {@link #claimAindaValido},
     * chamada na mesma unidade pela composicao de {@code RegistrarResultadoEfetivacao}.
     */
    void terminalizarPorConclusaoConcorrente(
            EntregaEfetivacaoReclamada claim, StatusSolicitacaoAumentoLimite terminalObservado, Instant agora);
}
