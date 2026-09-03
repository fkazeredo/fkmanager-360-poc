package com.fkmanager360.credito.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fkmanager360.credito.application.ResultadoSubmissao;
import com.fkmanager360.credito.domain.DecisaoCredito;
import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;
import io.swagger.v3.oas.annotations.media.Schema;

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
        @Schema(format = "uuid") String solicitacaoId,
        @Schema(example = "10001") String contaId,
        @Schema(description = "Neste ticket a decisao automatica sempre ocorre na mesma resposta, "
                + "entao so AGUARDANDO_EFETIVACAO e REJEITADA sao alcancaveis aqui.",
                allowableValues = {"AGUARDANDO_EFETIVACAO", "REJEITADA"}) String status,
        @Schema(description = "O vigente CONFIRMADO pelo CoreLegado e congelado no contexto -- "
                + "nunca o solicitado. Em centavos.", example = "500000") long limiteChequeEspecialVigente,
        @Schema(example = "600000") long limiteSolicitado,
        @Schema(description = "PRESENCA e a pendencia: presente e igual a limiteSolicitado quando "
                + "status=AGUARDANDO_EFETIVACAO; ausente do JSON quando REJEITADA. Nao e booleano.",
                example = "600000", nullable = true) Long limiteSolicitadoPendenteDeEfetivacao,
        DecisaoResponse decisao,
        Instant registradaEm) {

    record DecisaoResponse(
            @Schema(allowableValues = {"APROVADA", "REJEITADA"}) String resultado,
            @Schema(description = "Codigo estavel do dominio, nunca frase de interface. "
                    + "PERFIL_RISCO_INCOMPATIVEL nunca expoe a classificacao de risco bruta.",
                    allowableValues = {"CONTA_NAO_ELEGIVEL", "PERFIL_RISCO_INCOMPATIVEL",
                            "DENTRO_DA_POLITICA_AUTOMATICA", "FORA_DA_POLITICA_AUTOMATICA"}) String motivo,
            @Schema(example = "v1") String versaoPoliticaCredito,
            Instant decididaEm) {

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
