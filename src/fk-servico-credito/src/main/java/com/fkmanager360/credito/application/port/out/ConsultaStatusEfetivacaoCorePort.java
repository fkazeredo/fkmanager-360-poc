package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.ProtocoloCore;

/**
 * Porta de saida para a consulta de status de uma efetivacao ao CoreLegado (#0006). O adapter que
 * implementa esta porta e a ACL de reconciliacao -- o unico lugar, junto de
 * {@code InstrucaoEfetivacaoCorePort} (#0004), que conhece o vocabulario host-centric desta
 * operacao (ADR-0005). Nunca lanca excecao para o chamador.
 *
 * <p>Duas operacoes, nao uma com parametro opcional: a consulta e recuperavel por
 * {@link ProtocoloCore} quando conhecido e por {@link EfetivacaoId} quando o aceite se perdeu
 * (ADR-0009, emenda) -- sao duas perguntas host-centric genuinamente distintas, e
 * {@code ReconciliarEfetivacoes} decide qual fazer a partir do que
 * {@code EfetivacaoReconciliacaoReclamada} carrega.
 */
public interface ConsultaStatusEfetivacaoCorePort {

    ResultadoConsultaStatusCore consultarPorProtocolo(ProtocoloCore protocolo);

    ResultadoConsultaStatusCore consultarPorEfetivacaoId(EfetivacaoId efetivacaoId);
}
