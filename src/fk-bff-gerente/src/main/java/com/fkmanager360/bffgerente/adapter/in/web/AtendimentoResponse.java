package com.fkmanager360.bffgerente.adapter.in.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * O modelo de apresentacao da tela de atendimento, montado pelo BFF a partir dos dois contextos
 * (AC30). E o unico lugar do sistema onde Cliente, ContaCorrente e LimiteChequeEspecialVigente
 * aparecem juntos -- e ele e apresentacao, nao agregado: ninguem o persiste, ninguem decide sobre
 * ele, e amanha uma tela diferente monta outro sem que nenhum contexto mude.
 *
 * <p>O limite continua em centavos: a formatacao em reais pertence ao app-gerente, e nenhum texto
 * de interface vem do backend.
 */
record AtendimentoResponse(
        ClienteResumo cliente,
        ContaResumo conta,
        @Schema(description = "Valor em CENTAVOS, como inteiro (ADR-0005). A formatacao em reais pertence ao "
                + "fk-app-gerente -- nenhum texto de interface vem do backend.", example = "500000") long limiteChequeEspecialVigente,
        @Schema(description = "Instante em que a plataforma leu o limite do CoreLegado.") Instant consultadoEm) {

    record ClienteResumo(
            @Schema(example = "1") String clienteId,
            String nome,
            @Schema(example = "***.456.789-**") String cpfMascarado) {
    }

    record ContaResumo(
            @Schema(example = "10001") String contaId,
            @Schema(example = "0001") String agencia) {
    }

    /**
     * Valida a presenca de cada campo obrigatorio antes de compor -- nenhum dos dois corpos e
     * confiado cegamente. Um {@code 2xx} com corpo vazio, truncado ou com {@code limite} ausente
     * vira {@link DependenciaRespostaInvalidaException} (502 na borda), nunca um
     * {@code NullPointerException} (500) nem um limite fabricado como zero.
     */
    static AtendimentoResponse de(ContextoAtendimentoResponse contexto, LimiteChequeEspecialVigenteResponse limite) {
        if (contexto == null || contexto.clienteId() == null || contexto.conta() == null) {
            throw new DependenciaRespostaInvalidaException(
                    "servico-carteira-clientes devolveu contexto de atendimento incompleto");
        }
        if (limite == null || limite.limiteChequeEspecialVigente() == null || limite.consultadoEm() == null) {
            throw new DependenciaRespostaInvalidaException(
                    "servico-credito devolveu limite vigente incompleto");
        }

        return new AtendimentoResponse(
                new ClienteResumo(contexto.clienteId(), contexto.nome(), contexto.cpfMascarado()),
                new ContaResumo(contexto.conta().contaId(), contexto.conta().agencia()),
                limite.limiteChequeEspecialVigente(),
                limite.consultadoEm());
    }
}
