package com.fkmanager360.credito.adapter.in.web;

import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.DadosCreditoCore;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * O que a API de Credito devolve nesta consulta -- e nada alem disso.
 *
 * <p>Fora, deliberadamente: a {@code ClassificacaoRiscoCreditoBase}, que e insumo interno da
 * politica e nunca e apresentada ao GerenteRelacionamento; a {@code situacaoConta}, cujo status
 * host bruto nao e exposto; e qualquer dado cadastral do Cliente, que pertence a
 * CarteiraClientes (AC30). Os tres sao lidos numa unica resposta do Core e param na fronteira
 * deste servico.
 *
 * <p>{@code limiteChequeEspecialVigente} em centavos, como inteiro (ADR-0005). A formatacao em
 * reais e do app-gerente -- nenhum texto de interface vem do backend.
 *
 * <p>{@code consultadoEm} e o instante em que esta plataforma leu os fatos do Core, e e o que
 * responde "este limite e de agora?". Nao e a data em que o host atualizou o limite.
 */
record LimiteChequeEspecialVigenteResponse(
        @Schema(example = "10001") String contaId,
        @Schema(description = "Em centavos, inteiro (ADR-0005).", example = "500000") long limiteChequeEspecialVigente,
        @Schema(description = "Instante em que esta plataforma capturou os fatos do CoreLegado -- "
                + "nunca a data em que o host atualizou o limite.", example = "2026-09-02T16:00:00Z") Instant consultadoEm) {

    static LimiteChequeEspecialVigenteResponse de(ContaId contaId, DadosCreditoCore dados) {
        return new LimiteChequeEspecialVigenteResponse(
                contaId.valor(),
                dados.limiteChequeEspecialVigente().centavos(),
                dados.consultadoEm());
    }
}
