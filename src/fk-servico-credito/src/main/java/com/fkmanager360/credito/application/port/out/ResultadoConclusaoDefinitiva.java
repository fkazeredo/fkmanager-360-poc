package com.fkmanager360.credito.application.port.out;

import java.time.Duration;
import java.util.Objects;

/**
 * Resultado de {@link EntregasEfetivacaoPort#concluirComFalhaDefinitiva}, distinto do
 * {@link ResultadoRegistroEntrega} generico usado pelos outros tres desfechos: e o UNICO caminho
 * de entrega que conclui a solicitacao, e por isso e o unico que carrega a permanencia em
 * AGUARDANDO_EFETIVACAO (AC36) -- forcar esse campo dentro do enum compartilhado obrigaria os
 * outros tres desfechos a carregar um valor que nunca usam.
 */
public sealed interface ResultadoConclusaoDefinitiva {

    record Aplicado(Duration permanenciaEmAguardandoEfetivacao) implements ResultadoConclusaoDefinitiva {
        public Aplicado {
            Objects.requireNonNull(permanenciaEmAguardandoEfetivacao, "permanenciaEmAguardandoEfetivacao e obrigatoria");
        }
    }

    record DescartadoClaimObsoleto() implements ResultadoConclusaoDefinitiva {
    }
}
