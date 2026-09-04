package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.MotivoFalhaEfetivacao;
import com.fkmanager360.credito.domain.ProtocoloCore;

import java.util.Objects;

/**
 * As quatro classes da taxonomia da ACL de efetivacao (spec, secao "Taxonomia de resultados na
 * ACL"; plano #0004, secao 6): aceite, transitorio, definitivo e indeterminado. Devolvido por
 * {@link InstrucaoEfetivacaoCorePort#entregar}, que NUNCA lanca excecao para o chamador -- toda
 * patologia observavel (transporte, HTTP, COD-RET, payload) e classificada aqui, dentro da ACL, e
 * nenhum {@code COD-RET} ou detalhe host atravessa para a aplicacao (ADR-0005).
 *
 * <p><b>Regra de ouro (decisao do Owner):</b> erro HTTP tecnico nunca produz {@link FalhaDefinitiva}
 * pelo status -- somente retorno de negocio autoritativo e reconhecido (COD-RET definitivo
 * conhecido) produz esta classe. Timeout, reset, 5xx, 429, 4xx inesperado, redirect ou
 * content-type incompativel nunca tem caminho sintatico ate {@link FalhaDefinitiva} (invariante 8
 * do ticket #0004: falha transitoria/indeterminada nunca vira FALHA_EFETIVACAO).
 */
public sealed interface ResultadoInstrucaoCore {

    /** Aceite: persiste {@link ProtocoloCore}; a solicitacao permanece AGUARDANDO_EFETIVACAO. */
    record Aceite(ProtocoloCore protocoloCore) implements ResultadoInstrucaoCore {
        public Aceite {
            Objects.requireNonNull(protocoloCore, "protocoloCore e obrigatorio");
        }
    }

    /**
     * Transitorio: timeout, connection reset, falha de I/O de transporte, HTTP 5xx, HTTP 429 ou
     * COD-RET de indisponibilidade conhecido. O dispatcher reagenda com o MESMO EfetivacaoId.
     * {@code detalheTecnico} e descricao curta e sanitizada -- nunca payload ou COD-RET cru
     * (ADR-0017).
     */
    record FalhaTransitoria(String detalheTecnico) implements ResultadoInstrucaoCore {
        public FalhaTransitoria {
            Objects.requireNonNull(detalheTecnico, "detalheTecnico e obrigatorio");
        }
    }

    /**
     * Definitivo: retorno de negocio autoritativo e conhecido de que a efetivacao nao pode ser
     * realizada (conta inexistente, conta bloqueada, instrucao invalida, limite vigente
     * divergente). Converge em {@code RegistrarResultadoEfetivacao} -&gt; FALHA_EFETIVACAO.
     */
    record FalhaDefinitiva(MotivoFalhaEfetivacao motivo) implements ResultadoInstrucaoCore {
        public FalhaDefinitiva {
            Objects.requireNonNull(motivo, "motivo e obrigatorio");
        }
    }

    /**
     * Indeterminado: COD-RET desconhecido, payload malformado, campo obrigatorio ausente, HTTP 4xx
     * inesperado (sem semantica definida pelo contrato), redirect ou content-type/payload
     * incompativel com o contrato -- inclusive o mesmo EfetivacaoId com payload incompatível
     * (COD-RET 207). Nao conclui nada: o dispatcher para sem transicionar a solicitacao.
     */
    record RespostaIndeterminada(String detalheTecnico) implements ResultadoInstrucaoCore {
        public RespostaIndeterminada {
            Objects.requireNonNull(detalheTecnico, "detalheTecnico e obrigatorio");
        }
    }
}
