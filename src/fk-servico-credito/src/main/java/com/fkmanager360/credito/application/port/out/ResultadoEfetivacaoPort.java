package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.AtorOperacao;
import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.ProtocoloCore;

import java.time.Instant;
import java.util.Optional;

/**
 * Porta de saida da conclusao atomica e idempotente da efetivacao (ADR-0009): a UNICA porta de
 * saida de {@code AGUARDANDO_EFETIVACAO} (e, quando #0006 existir, de
 * {@code EFETIVACAO_INDETERMINADA}). Correlaciona por {@link EfetivacaoId} -- nunca por
 * {@code ProtocoloCore}, que pode ser desconhecido quando o aceite se perdeu (ADR-0009, emenda).
 *
 * <p>Usada por #0004 (recusa definitiva ja no aceite) e, sem nenhuma segunda implementacao da
 * regra de conclusao, por #0005 (callback) e #0006 (reconciliacao). Nao conhece
 * {@code outbox_entrega} nem claim de dispatcher -- quando o resultado chega pela via da entrega,
 * quem compoe esta porta com o fencing e a terminalizacao (dentro de uma {@link TransacaoPort}
 * unica) e {@code RegistrarResultadoEfetivacao#executarSobClaim}, nunca esta porta sozinha.
 *
 * <p>{@link #registrarIndeterminacao} (#0006) e a UNICA porta de saida adicional de
 * {@code AGUARDANDO_EFETIVACAO}: ao contrario de {@link #registrar}, nao carrega um resultado
 * autoritativo do Core -- e a propria janela normal de recuperacao automatica esgotada (spec,
 * secao "Reconciliacao"). Nunca produz {@code FALHA_EFETIVACAO}.
 *
 * <p>{@code protocoloInformado} (#0005): o callback sempre o traz (todo callback pressupoe um
 * aceite previo com {@code numPrt} -- ver contrato em {@code CallbackEfetivacaoRequest}); o
 * dispatcher (#0004, via {@code executarSobClaim}) nunca o informa, porque aprender o protocolo
 * de aceite continua sendo responsabilidade exclusiva de {@code registrarAceite}. Quando presente
 * e o {@code ProtocoloCore} ainda nao e conhecido, esta porta o aprende (mesmo efeito de
 * {@code registrarAceite}, idempotente entre os dois caminhos); quando presente e diverge do ja
 * persistido, o existente NUNCA e sobrescrito (protocolo e o primeiro dos tres eixos que decidem
 * "identico" vs "contraditorio" -- ver {@link ResultadoRegistroEfetivacao}).
 */
public interface ResultadoEfetivacaoPort {

    ResultadoRegistroEfetivacao registrar(
            EfetivacaoId efetivacaoId,
            ResultadoEfetivacaoRecebido resultado,
            Optional<ProtocoloCore> protocoloInformado,
            AtorOperacao autor,
            Instant agora);

    /**
     * Transiciona {@code AGUARDANDO_EFETIVACAO -> EFETIVACAO_INDETERMINADA} quando a janela normal
     * de recuperacao automatica se esgota sem resultado autoritativo (#0006, AC16). Idempotente:
     * ja indeterminada ou ja terminal nao reescreve nada -- ver {@link ResultadoIndeterminacao}.
     */
    ResultadoIndeterminacao registrarIndeterminacao(EfetivacaoId efetivacaoId, Instant agora);
}
