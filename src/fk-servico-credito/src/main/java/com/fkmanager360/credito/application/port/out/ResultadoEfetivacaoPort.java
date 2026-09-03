package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.AtorOperacao;
import com.fkmanager360.credito.domain.EfetivacaoId;

import java.time.Instant;

/**
 * Porta de saida da conclusao atomica e idempotente da efetivacao (ADR-0009): a UNICA porta de
 * saida de {@code AGUARDANDO_EFETIVACAO} (e, quando #0006 existir, de
 * {@code EFETIVACAO_INDETERMINADA}). Correlaciona por {@link EfetivacaoId} -- nunca por
 * {@code ProtocoloCore}, que pode ser desconhecido quando o aceite se perdeu (ADR-0009, emenda).
 *
 * <p>Usada por #0004 (recusa definitiva ja no aceite) e, sem nenhuma segunda implementacao da
 * regra de conclusao, por #0005 (callback) e #0006 (reconciliacao). Nao conhece
 * {@code outbox_entrega} nem claim de dispatcher -- isso e responsabilidade exclusiva de
 * {@link EntregasEfetivacaoPort}, que a invoca de dentro da mesma transacao quando o resultado
 * chega pela via da entrega (plano #0004, secao 9).
 */
public interface ResultadoEfetivacaoPort {

    ResultadoRegistroEfetivacao registrar(
            EfetivacaoId efetivacaoId, ResultadoEfetivacaoRecebido resultado, AtorOperacao autor, Instant agora);
}
