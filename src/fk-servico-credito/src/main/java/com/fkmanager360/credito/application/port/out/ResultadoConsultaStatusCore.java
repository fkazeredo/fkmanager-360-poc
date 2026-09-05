package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.MotivoFalhaEfetivacao;
import com.fkmanager360.credito.domain.ProtocoloCore;

import java.util.Objects;

/**
 * Taxonomia da consulta de status ao CoreLegado (#0006; spec, secao "Contrato do
 * simulador-core-legado"): distinta da taxonomia de quatro classes da instrucao de efetivacao
 * (#0004, {@link ResultadoInstrucaoCore}) porque a PERGUNTA e diferente da ENTREGA -- aqui nao
 * existe "aceite" nem "transitorio com retry automatico" (o reconciliador nunca reenvia), e existe
 * um resultado novo que a instrucao nao tinha: "ainda em processamento".
 *
 * <p><b>{@link Desconhecida} NAO e evidencia autoritativa de "nunca aceito" neste slice</b> (decisao
 * do Owner): o simulador perde seu store em memoria a cada restart, entao "efetivacao desconhecida"
 * pode significar tanto "nunca existiu" quanto "existiu, mas o simulador reiniciou" -- tratar isso
 * como {@link FalhaDefinitiva} gravaria um fato possivelmente falso (ADR-0009, emenda). O
 * reconciliador reagenda e, esgotada a janela, converge em {@code EFETIVACAO_INDETERMINADA} como
 * qualquer outra resposta nao autoritativa -- nunca em {@code FALHA_EFETIVACAO}.
 */
public sealed interface ResultadoConsultaStatusCore {

    /** Autoritativo: o Core confirma que a efetivacao foi aplicada. */
    record Efetivada(ProtocoloCore protocolo, long limiteEfetivadoCentavos) implements ResultadoConsultaStatusCore {
        public Efetivada {
            Objects.requireNonNull(protocolo, "protocolo e obrigatorio");
            if (limiteEfetivadoCentavos <= 0) {
                throw new IllegalArgumentException("limiteEfetivadoCentavos deve ser positivo: " + limiteEfetivadoCentavos);
            }
        }
    }

    /** Autoritativo: o Core confirma que a efetivacao nao foi, e nao sera, aplicada. */
    record FalhaDefinitiva(MotivoFalhaEfetivacao motivo) implements ResultadoConsultaStatusCore {
        public FalhaDefinitiva {
            Objects.requireNonNull(motivo, "motivo e obrigatorio");
        }
    }

    /** Nao autoritativo: o Core reconhece a operacao, mas ainda nao concluiu o processamento. */
    record EmProcessamento() implements ResultadoConsultaStatusCore {
    }

    /**
     * Nao autoritativo: o Core nao reconhece o identificador consultado. Nunca tratado como
     * evidencia de que a efetivacao nunca ocorreu -- ver Javadoc da interface.
     */
    record Desconhecida() implements ResultadoConsultaStatusCore {
    }

    /**
     * Nao autoritativo: falha de transporte, timeout, payload malformado, COD-RET desconhecido ou
     * qualquer patologia que a ACL nao saiba interpretar. Mesma semantica de
     * {@code RespostaIndeterminada} em #0004: nao conclui nada.
     */
    record Indeterminada(String detalheTecnico) implements ResultadoConsultaStatusCore {
        public Indeterminada {
            Objects.requireNonNull(detalheTecnico, "detalheTecnico e obrigatorio");
        }
    }
}
