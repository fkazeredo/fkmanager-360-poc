package com.fkmanager360.credito.adapter.in.web;

import com.fkmanager360.credito.application.usecase.ConsultarLimiteChequeEspecialVigente;
import com.fkmanager360.credito.domain.ClienteId;
import com.fkmanager360.credito.domain.ContaId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * AC29 (parcial): o limite apresentado e o LimiteChequeEspecialVigente que o CoreLegado reconhece
 * no momento da consulta, lido pela ACL deste contexto. Nenhum valor local ou derivado e
 * apresentado como limite do Cliente (ADR-0002).
 *
 * <p>O {@code clienteId} faz parte do caminho porque a autorizacao em CarteiraClientes e por
 * Cliente e precisa acontecer <b>antes</b> de qualquer leitura no Core (AC23). Ele nao e aceito
 * como verdade sobre a quem a conta pertence -- essa confirmacao e autoritativa e vem de
 * CarteiraClientes contra o Core.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "limite", description = "Leitura do LimiteChequeEspecialVigente reconhecido pelo CoreLegado.")
public class LimiteController {

    private final ConsultarLimiteChequeEspecialVigente consultarLimite;

    @Operation(
            operationId = "consultarLimiteChequeEspecialVigente",
            summary = "LimiteChequeEspecialVigente da ContaCorrente, lido do CoreLegado agora",
            description = "O clienteId compoe o caminho porque a autorizacao de recurso em "
                    + "CarteiraClientes e por Cliente e precisa acontecer antes de qualquer leitura "
                    + "no Core; nao e aceito como verdade sobre a quem a conta pertence.")
    @SecurityRequirement(name = "bearerJwt", scopes = "credito.leitura")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Limite vigente reconhecido pelo CoreLegado no instante da consulta.",
                    content = @Content(schema = @Schema(implementation = LimiteChequeEspecialVigenteResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Identificador fora do formato esperado. codigo = IDENTIFICADOR_INVALIDO.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401",
                    description = "Sem token, token expirado, assinatura invalida, issuer ou audience incorretos.",
                    content = @Content),
            @ApiResponse(responseCode = "403",
                    description = "Sem direito de atendimento atual sobre o Cliente -- nenhuma chamada ao "
                            + "CoreLegado e emitida. codigo = SEM_DIREITO_DE_ATENDIMENTO.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404",
                    description = "Conta nao pertence ao Cliente, ou o CoreLegado nao a conhece. codigo = CONTA_NAO_ENCONTRADA.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502",
                    description = "CoreLegado respondeu algo que a ACL nao sabe interpretar. codigo = DEPENDENCIA_INDISPONIVEL.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503",
                    description = "CoreLegado ou fk-servico-carteira-clientes indisponivel. codigo = DEPENDENCIA_INDISPONIVEL.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "504",
                    description = "Timeout ao consultar o CoreLegado. codigo = DEPENDENCIA_INDISPONIVEL.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    })
    @GetMapping(path = "/clientes/{clienteId}/contas/{contaId}/limite-cheque-especial-vigente",
            produces = MediaType.APPLICATION_JSON_VALUE)
    LimiteChequeEspecialVigenteResponse consultar(
            @Parameter(description = "Identificador do Cliente, sem zero-padding.", example = "1")
            @PathVariable String clienteId,
            @Parameter(description = "Identificador da ContaCorrente, sem zero-padding.", example = "10001")
            @PathVariable String contaId) {

        ContaId conta = new ContaId(contaId);
        return LimiteChequeEspecialVigenteResponse.de(
                conta, consultarLimite.executar(new ClienteId(clienteId), conta));
    }
}
