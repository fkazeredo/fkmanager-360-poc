package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.MotivoFalhaEfetivacao;
import com.fkmanager360.credito.domain.ProtocoloCore;

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
     * TX-B (definitivo): conclui a solicitacao via {@code ResultadoEfetivacaoPort} (a MESMA porta
     * usada por callback/reconciliacao em #0005/#0006, chamada dentro da mesma transacao) e fecha
     * a entrega como {@code FALHA_DEFINITIVA} -- atomico com a checagem de fencing. Retorno
     * dedicado ({@link ResultadoConclusaoDefinitiva}, nao o {@link ResultadoRegistroEntrega}
     * generico): e o unico desfecho que conclui a solicitacao, e por isso o unico que carrega a
     * permanencia em AGUARDANDO_EFETIVACAO (AC36).
     */
    ResultadoConclusaoDefinitiva concluirComFalhaDefinitiva(
            EntregaEfetivacaoReclamada claim, MotivoFalhaEfetivacao motivo, Instant agora);
}
