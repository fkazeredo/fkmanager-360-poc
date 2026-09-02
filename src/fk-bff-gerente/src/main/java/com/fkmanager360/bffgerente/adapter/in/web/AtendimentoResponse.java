package com.fkmanager360.bffgerente.adapter.in.web;

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
record AtendimentoResponse(ClienteResumo cliente, ContaResumo conta, long limiteChequeEspecialVigente, Instant consultadoEm) {

    record ClienteResumo(String clienteId, String nome, String cpfMascarado) {
    }

    record ContaResumo(String contaId, String agencia) {
    }

    static AtendimentoResponse de(ContextoAtendimentoResponse contexto, LimiteVigenteResponse limite) {
        return new AtendimentoResponse(
                new ClienteResumo(contexto.clienteId(), contexto.nome(), contexto.cpfMascarado()),
                new ContaResumo(contexto.conta().contaId(), contexto.conta().agencia()),
                limite.limiteChequeEspecialVigente(),
                limite.consultadoEm());
    }
}
