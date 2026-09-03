package com.fkmanager360.credito.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fkmanager360.credito.application.ResultadoSubmissao;
import com.fkmanager360.credito.domain.DecisaoCredito;
import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;

import java.time.Instant;

/**
 * O que a API de Credito devolve na submissao (spec, secao "Contratos e erros"; plano #0003, secao
 * 9). Estado, motivo, valores de negocio e a decisao -- nunca {@code ClassificacaoRiscoCreditoBase}
 * (AC3), nunca {@code situacaoConta} bruta, nunca texto de interface (o pt-BR pertence ao
 * app-gerente).
 *
 * <p><b>{@code limiteSolicitadoPendenteDeEfetivacao} -- presenca E a pendencia</b> (plano #0003,
 * secao "limiteSolicitadoPendenteDeEfetivacao"): nao e booleano. E o valor pendente em centavos
 * quando {@code status == AGUARDANDO_EFETIVACAO}, e o campo fica AUSENTE do JSON (nao {@code null}
 * expresso, ausente de verdade) em qualquer outro status -- nesta etapa, {@code REJEITADA}. Assim
 * o frontend nao tem como apresentar uma rejeicao como "pendente de efetivacao" (AC29 parcial).
 * {@code @JsonInclude(NON_NULL)} na classe e o mecanismo: nao existe configuracao global de
 * {@code ObjectMapper} neste modulo que precise ser respeitada em vez da anotacao local.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record SolicitacaoAumentoLimiteResponse(
        String solicitacaoId,
        String contaId,
        String status,
        long limiteChequeEspecialVigente,
        long limiteSolicitado,
        Long limiteSolicitadoPendenteDeEfetivacao,
        DecisaoResponse decisao,
        Instant registradaEm) {

    record DecisaoResponse(String resultado, String motivo, String versaoPoliticaCredito, Instant decididaEm) {

        static DecisaoResponse de(DecisaoCredito decisao) {
            return new DecisaoResponse(
                    decisao.resultado().name(),
                    decisao.motivo().name(),
                    decisao.versaoPoliticaCredito().valor(),
                    decisao.decididaEm());
        }
    }

    /**
     * {@code registradaEm}: nem {@link ResultadoSubmissao} nem os casos de uso devolvem o instante
     * de registro da solicitacao diretamente hoje -- o record carrega os fatos de decisao, nao o
     * timestamp de criacao da linha. O controller usa o mesmo {@code Instant} que ele proprio
     * passou a {@code registrarSolicitacaoAumentoLimite.executar(comando, agora)} como aproximacao
     * aceitavel para este ticket: e o mesmo instante logico da requisicao, e num replay o valor
     * apresentado e o do momento da releitura, nao o do registro original -- suficiente para #0003,
     * que nao expoe consulta/listagem de solicitacoes (isso e #0007).
     */
    static SolicitacaoAumentoLimiteResponse de(ResultadoSubmissao resultado, Instant registradaEm) {
        Long pendente = resultado.status() == StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO
                ? resultado.limiteSolicitado().centavos()
                : null;

        return new SolicitacaoAumentoLimiteResponse(
                resultado.solicitacaoId().valor().toString(),
                resultado.contaId().valor(),
                resultado.status().name(),
                resultado.limiteChequeEspecialVigente().centavos(),
                resultado.limiteSolicitado().centavos(),
                pendente,
                DecisaoResponse.de(resultado.decisao()),
                registradaEm);
    }
}
