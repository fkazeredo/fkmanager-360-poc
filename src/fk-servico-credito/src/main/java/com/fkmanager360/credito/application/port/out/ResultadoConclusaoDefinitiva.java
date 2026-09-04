package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;

import java.time.Duration;
import java.util.Objects;

/**
 * Resultado da conclusao definitiva sob claim ({@code RegistrarResultadoEfetivacao#executarSobClaim}),
 * distinto do {@link ResultadoRegistroEntrega} generico usado pelos outros tres desfechos de
 * entrega: e o UNICO caminho que conclui a solicitacao, e por isso o unico que carrega a
 * permanencia em AGUARDANDO_EFETIVACAO (AC36) -- forcar esse campo dentro do enum compartilhado
 * obrigaria os outros tres desfechos a carregar um valor que nunca usam.
 */
public sealed interface ResultadoConclusaoDefinitiva {

    record Aplicado(Duration permanenciaEmAguardandoEfetivacao) implements ResultadoConclusaoDefinitiva {
        public Aplicado {
            Objects.requireNonNull(permanenciaEmAguardandoEfetivacao, "permanenciaEmAguardandoEfetivacao e obrigatoria");
        }
    }

    /**
     * Outro caminho (callback, #0005) ja terminalizou a solicitacao antes desta chamada sob claim
     * concluir -- o terminal PERSISTIDO e autoritativo, e o resultado que o dispatcher trazia
     * perde autoridade de escrita (regra normativa do Owner, #0005): nem status, nem motivo, nem
     * protocolo de {@code solicitacao_aumento_limite} sao tocados, e nenhum historico novo e
     * gravado. {@code terminalObservado} dita como a entrega termina tecnicamente --
     * {@code EFETIVADA} vira {@code ACEITA}, {@code FALHA_EFETIVACAO} vira
     * {@code FALHA_DEFINITIVA} ({@code EntregasEfetivacaoPort#terminalizarPorConclusaoConcorrente})
     * -- nunca a partir do resultado perdedor. {@code contraditoria} distingue, so para fins de
     * anomalia observavel, se o resultado perdedor coincidia com o vencedor (nenhuma anomalia alem
     * da propria concorrencia) ou o contradizia (anomalia adicional).
     */
    record ConcluidaPorOutroCaminho(StatusSolicitacaoAumentoLimite terminalObservado, boolean contraditoria)
            implements ResultadoConclusaoDefinitiva {
        public ConcluidaPorOutroCaminho {
            Objects.requireNonNull(terminalObservado, "terminalObservado e obrigatorio");
        }
    }

    record DescartadoClaimObsoleto() implements ResultadoConclusaoDefinitiva {
    }
}
