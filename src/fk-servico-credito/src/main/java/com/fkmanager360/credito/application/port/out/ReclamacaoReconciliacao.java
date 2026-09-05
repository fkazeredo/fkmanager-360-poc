package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;

import java.util.Objects;

/**
 * Saida de {@link ReconciliacaoEfetivacaoPort#reclamarProxima} (TX-A, #0006).
 */
public sealed interface ReclamacaoReconciliacao {

    /** Nenhum ciclo de reconciliacao elegivel neste tick. */
    record NenhumaPendente() implements ReclamacaoReconciliacao {
    }

    /**
     * A solicitacao correlacionada ja esta terminal (concluida por outro caminho -- callback,
     * tipicamente) no momento do proprio claim: terminaliza a linha de reconciliacao DENTRO da
     * mesma TX-A, sem consultar o Core (plano #0006 -- "o dispatcher entrega, o reconciliador
     * pergunta", e aqui nem pergunta e necessario).
     */
    record JaTerminalDescartada(StatusSolicitacaoAumentoLimite statusPersistido) implements ReclamacaoReconciliacao {
        public JaTerminalDescartada {
            Objects.requireNonNull(statusPersistido, "statusPersistido e obrigatorio");
        }
    }

    record Reclamada(EfetivacaoReconciliacaoReclamada claim) implements ReclamacaoReconciliacao {
        public Reclamada {
            Objects.requireNonNull(claim, "claim e obrigatorio");
        }
    }
}
