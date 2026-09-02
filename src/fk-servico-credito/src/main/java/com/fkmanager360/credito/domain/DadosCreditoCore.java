package com.fkmanager360.credito.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Os fatos de credito lidos do CoreLegado numa <b>unica</b> consulta, junto com o instante dessa
 * consulta e a identificacao logica da fonte. Existe para que a procedencia seja registrada uma
 * vez para o conjunto, e nao repetida campo a campo.
 *
 * <p><b>{@code consultadoEm} e o instante em que esta plataforma capturou os fatos com sucesso</b>
 * -- nada mais. Em particular, nao e a data em que o proprio host atualizou o limite: o contrato
 * host-centric tem um campo para isso ({@code DAT-ATU-LIM}), e confundir os dois trocaria "quando
 * a fonte mudou" por "quando perguntamos a ela". Sao perguntas diferentes, com respostas
 * diferentes, e a segunda e a que responde "estes fatos sao de agora?".
 *
 * <p>{@code fonte} e identificacao <b>logica</b> -- o sistema de onde o dado veio --, nunca URL,
 * host ou porta: procedencia de negocio nao muda porque o endereco de rede mudou.
 */
public record DadosCreditoCore(
        LimiteChequeEspecialVigente limiteChequeEspecialVigente,
        SituacaoConta situacaoConta,
        ClassificacaoRiscoCreditoBase classificacaoRiscoCreditoBase,
        Instant consultadoEm,
        String fonte) {

    public DadosCreditoCore {
        Objects.requireNonNull(limiteChequeEspecialVigente, "limiteChequeEspecialVigente");
        Objects.requireNonNull(situacaoConta, "situacaoConta");
        Objects.requireNonNull(classificacaoRiscoCreditoBase, "classificacaoRiscoCreditoBase");
        Objects.requireNonNull(consultadoEm, "consultadoEm");
        if (fonte == null || fonte.isBlank()) {
            throw new IllegalArgumentException("DadosCreditoCore sem identificacao da fonte");
        }
    }
}
