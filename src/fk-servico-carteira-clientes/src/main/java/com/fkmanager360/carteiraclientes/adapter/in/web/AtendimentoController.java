package com.fkmanager360.carteiraclientes.adapter.in.web;

import com.fkmanager360.carteiraclientes.application.usecase.ConfirmarDireitoDeAtendimento;
import com.fkmanager360.carteiraclientes.application.usecase.ConsultarContextoAtendimento;
import com.fkmanager360.carteiraclientes.application.usecase.ListarContasDoCliente;
import com.fkmanager360.carteiraclientes.domain.ClienteId;
import com.fkmanager360.carteiraclientes.domain.ContaId;
import com.fkmanager360.carteiraclientes.domain.GerenteId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * AC22, AC23 e AC30: selecionar um Cliente devolve suas ContaCorrentes; a autorizacao de recurso
 * e produzida aqui -- pelo backend dono da associacao --, nunca pelo app-gerente ou pelo
 * bff-gerente (ADR-0007).
 *
 * <p>Duas operacoes de atendimento por conta, com publicos diferentes de proposito:
 * {@code /direito-de-atendimento} confirma a legitimidade do atendimento sem devolver nada
 * cadastral -- e o que servico-credito consome, para que uma falha na consulta de dados mestres
 * do Cliente nunca impeca a leitura do limite; {@code /contexto-atendimento} devolve o contexto
 * rico -- nome, CPF, conta -- que o bff-gerente usa para compor a tela (AC30).
 *
 * <p>O {@code gerenteId} vem do claim {@code sub} do token ja validado na borda e nunca de
 * parametro algum; o {@code clienteId} vem do caminho, e existe justamente para que a
 * verificacao de direito possa acontecer <b>antes</b> de qualquer chamada ao CoreLegado. Ele nao
 * e tratado como verdade sobre a quem a conta pertence -- isso quem afirma e o Core.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "atendimento", description = "ContaCorrentes de um Cliente e contexto de atendimento por conta. "
        + "Toda operacao aqui e precedida da verificacao local do direito de atendimento atual (ADR-0007).")
public class AtendimentoController {

    private final ListarContasDoCliente listarContasDoCliente;
    private final ConfirmarDireitoDeAtendimento confirmarDireitoDeAtendimento;
    private final ConsultarContextoAtendimento consultarContextoAtendimento;

    @Operation(
            operationId = "listarContasDoCliente",
            summary = "ContaCorrentes de um Cliente da carteira do gerente autenticado",
            description = "Completa o AC22: selecionar um Cliente devolve suas ContaCorrentes. Sem "
                    + "paginacao -- a quantidade de contas de um Cliente e naturalmente pequena (ADR-0010).")
    @SecurityRequirement(name = "bearerJwt", scopes = "carteira.leitura")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Contas que o CoreLegado reconhece para aquele Cliente.",
                    content = @Content(schema = @Schema(implementation = ContasResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "Sem token valido para este Resource Server.",
                    content = @Content),
            @ApiResponse(responseCode = "403",
                    description = "Sem direito de atendimento atual sobre aquele Cliente -- nenhuma chamada ao "
                            + "CoreLegado e emitida (AC23). Tambem 403 sem o scope carteira.leitura ou sem o "
                            + "papel GERENTE_RELACIONAMENTO.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502",
                    description = "fk-simulador-core-legado respondeu algo que a ACL nao sabe interpretar.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503",
                    description = "fk-simulador-core-legado indisponivel.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "504",
                    description = "Timeout ao consultar fk-simulador-core-legado.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    })
    @GetMapping("/clientes/{clienteId}/contas")
    ContasResponse listarContas(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Identificador do Cliente, sem zero-padding.", example = "1",
                    schema = @Schema(pattern = "^[0-9]{1,10}$"))
            @PathVariable String clienteId) {
        return ContasResponse.de(listarContasDoCliente.executar(
                new GerenteId(jwt.getSubject()), new ClienteId(clienteId)));
    }

    /**
     * Confirmacao estreita do direito de atendimento: 204 quando legitimo, 403 sem vinculo com o
     * Cliente, 404 quando a conta nao e dele. Nenhum corpo, porque nenhum consumidor deste
     * endpoint precisa de mais do que a resposta binaria -- os dois identificadores ja vieram no
     * caminho da propria requisicao.
     */
    @Operation(
            operationId = "confirmarDireitoDeAtendimento",
            summary = "Confirma o direito de atendimento sobre uma conta, sem dado cadastral algum",
            description = "Operacao estreita de autorizacao (AC23): confirma vinculo atual com o Cliente e que "
                    + "a conta pedida e dele segundo o CoreLegado, sem devolver nome, CPF ou qualquer outro "
                    + "dado cadastral. E o que fk-servico-credito consome (AC21).")
    @SecurityRequirement(name = "bearerJwt", scopes = "carteira.leitura")
    @ApiResponses({
            @ApiResponse(responseCode = "204",
                    description = "O gerente tem direito de atendimento atual sobre o Cliente, e o CoreLegado "
                            + "confirma que a conta e dele. Sem corpo.",
                    content = @Content),
            @ApiResponse(responseCode = "401",
                    description = "Sem token valido para este Resource Server.",
                    content = @Content),
            @ApiResponse(responseCode = "403",
                    description = "Sem direito de atendimento atual sobre aquele Cliente -- nenhuma chamada ao "
                            + "CoreLegado e emitida (AC23).",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404",
                    description = "A conta nao esta entre as que o CoreLegado reconhece para aquele Cliente.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502",
                    description = "fk-simulador-core-legado respondeu algo que a ACL nao sabe interpretar.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503",
                    description = "fk-simulador-core-legado indisponivel.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "504",
                    description = "Timeout ao consultar fk-simulador-core-legado.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    })
    @GetMapping("/clientes/{clienteId}/contas/{contaId}/direito-de-atendimento")
    ResponseEntity<Void> confirmarDireitoDeAtendimento(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Identificador do Cliente, sem zero-padding.", example = "1",
                    schema = @Schema(pattern = "^[0-9]{1,10}$"))
            @PathVariable String clienteId,
            @Parameter(description = "Identificador da ContaCorrente, sem zero-padding.", example = "10001",
                    schema = @Schema(pattern = "^[0-9]{1,10}$"))
            @PathVariable String contaId) {

        confirmarDireitoDeAtendimento.executar(
                new GerenteId(jwt.getSubject()), new ClienteId(clienteId), new ContaId(contaId));
        return ResponseEntity.noContent().build();
    }

    @Operation(
            operationId = "consultarContextoAtendimento",
            summary = "Contexto de atendimento de uma conta -- identidade do Cliente e a conta atendida",
            description = "Operacao rica, consumida por fk-bff-gerente para compor a tela de atendimento com "
                    + "nome, CPF e conta (AC30). fk-servico-credito NAO consome esta operacao -- usa a "
                    + "operacao estreita /direito-de-atendimento.")
    @SecurityRequirement(name = "bearerJwt", scopes = "carteira.leitura")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "O gerente tem direito de atendimento atual sobre o Cliente, e o CoreLegado "
                            + "confirma que a conta e dele.",
                    content = @Content(schema = @Schema(implementation = ContextoAtendimentoResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "Sem token valido para este Resource Server.",
                    content = @Content),
            @ApiResponse(responseCode = "403",
                    description = "Sem direito de atendimento atual sobre aquele Cliente -- nenhuma chamada ao "
                            + "CoreLegado e emitida (AC23).",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404",
                    description = "A conta nao esta entre as que o CoreLegado reconhece para aquele Cliente. "
                            + "404, e nao 403: a autorizacao sobre o Cliente ja passou.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502",
                    description = "fk-simulador-core-legado respondeu algo que a ACL nao sabe interpretar.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503",
                    description = "fk-simulador-core-legado indisponivel.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "504",
                    description = "Timeout ao consultar fk-simulador-core-legado.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    })
    @GetMapping("/clientes/{clienteId}/contas/{contaId}/contexto-atendimento")
    ContextoAtendimentoResponse consultarContextoAtendimento(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Identificador do Cliente, sem zero-padding.", example = "1",
                    schema = @Schema(pattern = "^[0-9]{1,10}$"))
            @PathVariable String clienteId,
            @Parameter(description = "Identificador da ContaCorrente, sem zero-padding.", example = "10001",
                    schema = @Schema(pattern = "^[0-9]{1,10}$"))
            @PathVariable String contaId) {

        return ContextoAtendimentoResponse.de(consultarContextoAtendimento.executar(
                new GerenteId(jwt.getSubject()), new ClienteId(clienteId), new ContaId(contaId)));
    }
}
