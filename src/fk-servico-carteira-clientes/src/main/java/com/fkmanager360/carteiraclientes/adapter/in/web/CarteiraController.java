package com.fkmanager360.carteiraclientes.adapter.in.web;

import com.fkmanager360.carteiraclientes.application.usecase.ListarClientesDaCarteira;
import com.fkmanager360.carteiraclientes.domain.GerenteId;
import com.fkmanager360.carteiraclientes.domain.Pagination;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AC22: o gerente autenticado ve somente os Clientes da sua CarteiraClientes, paginada. O
 * gerenteId vem do claim {@code sub} do token ja validado pela borda -- este controller nao
 * reimplementa autenticacao, so traduz identidade autenticada em conceito de aplicacao
 * (ADR-0007).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "carteira", description = "Leitura da CarteiraClientes do gerente autenticado.")
public class CarteiraController {

    private final ListarClientesDaCarteira listarClientesDaCarteira;

    @Operation(
            operationId = "listarClientesDaCarteira",
            summary = "Pagina de Clientes da CarteiraClientes do gerente autenticado",
            description = "O gerenteId vem do claim sub do token bearer ja validado pela borda "
                    + "(issuer + audience); este endpoint nao reimplementa autenticacao (AC22, ADR-0007).")
    @SecurityRequirement(name = "bearerJwt", scopes = "carteira.leitura")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Pagina da carteira do gerente autenticado.",
                    content = @Content(schema = @Schema(implementation = ClientesPageResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Parametro de paginacao invalido (pagina negativa, ou tamanho fora de 1-100).",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401",
                    description = "Sem token, token expirado, assinatura invalida, issuer incorreto ou audience incorreta (AC21).",
                    content = @Content),
            @ApiResponse(responseCode = "403",
                    description = "Token valido mas sem o scope carteira.leitura ou sem o papel GERENTE_RELACIONAMENTO "
                            + "(ADR-0015: scope e papel sao perguntas distintas, ambos precisam valer).",
                    content = @Content),
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
    @GetMapping("/carteira/clientes")
    ClientesPageResponse listar(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            // name= explicito: o parametro de query publico ("pagina"/"tamanho") e contrato HTTP
            // ja exercitado por Angular/Playwright/curl -- so o identificador Java interno segue
            // a convencao tecnica em ingles.
            @Parameter(description = "Pagina zero-based.", example = "0")
            @RequestParam(name = "pagina", defaultValue = "0") int page,
            @Parameter(description = "Tamanho de pagina (1-100).", example = "20")
            @RequestParam(name = "tamanho", defaultValue = "" + Pagination.DEFAULT_SIZE) int size) {

        GerenteId gerenteId = new GerenteId(jwt.getSubject());
        var resultado = listarClientesDaCarteira.executar(gerenteId, new Pagination(page, size));
        return ClientesPageResponse.de(resultado);
    }
}
