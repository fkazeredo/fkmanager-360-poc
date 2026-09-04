package com.fkmanager360.credito.adapter.out.legacy;

import com.fkmanager360.credito.application.port.out.IntencaoEfetivacao;

/**
 * Instrucao funcional minima ao CoreLegado (spec, secao "EfetivacaoId, instrucao e ProtocoloCore"):
 * {@code idEft} e {@code idCor} carregam UUID por extenso (identidade de negocio, nao formato
 * host-centric numerico); {@code numCta} e as duas parcelas monetarias seguem o padrao host
 * (ADR-0005) via {@link HostFormat}.
 */
record EfetivacaoLegadoRequest(String idEft, String numCta, String vlrLimChqEspEsp, String vlrLimNov, String idCor) {

    static EfetivacaoLegadoRequest de(IntencaoEfetivacao intencao) {
        return new EfetivacaoLegadoRequest(
                intencao.efetivacaoId().valor().toString(),
                HostFormat.toCodigoHost(intencao.contaId().valor()),
                HostFormat.toValorMonetarioHost(intencao.limiteChequeEspecialVigenteEsperado().centavos()),
                HostFormat.toValorMonetarioHost(intencao.limiteSolicitado().centavos()),
                intencao.correlationId().valor().toString());
    }
}
