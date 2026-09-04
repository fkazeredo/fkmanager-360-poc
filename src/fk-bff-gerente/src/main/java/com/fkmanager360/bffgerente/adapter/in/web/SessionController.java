package com.fkmanager360.bffgerente.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * O suficiente para o Angular saber quem esta logado sem inspecionar o cookie -- que ele nao
 * consegue e nem deve (HttpOnly). Nenhum token atravessa esta fronteira.
 */
@RestController
@Tag(name = "sessao", description = "Identidade da sessao corrente, sem expor token.")
public class SessionController {

    @Operation(
            operationId = "consultarSessaoAtual",
            summary = "Identidade do GerenteRelacionamento autenticado na sessao corrente",
            description = "O suficiente para o Angular saber quem esta logado sem inspecionar o cookie de "
                    + "sessao, que e HttpOnly e portanto inacessivel a JavaScript.")
    @SecurityRequirement(name = "cookieSessao")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sessao autenticada.",
                    content = @Content(schema = @Schema(implementation = SessionResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "Sem sessao autenticada (sem cookie, ou cookie invalido/expirado).",
                    content = @Content),
    })
    // Path publico, ja contrato exercitado por Angular/Playwright -- nao muda so porque a classe
    // Java virou tecnica em ingles.
    @GetMapping(path = "/api/sessao", produces = "application/json")
    SessionResponse currentSession(@Parameter(hidden = true) @AuthenticationPrincipal OidcUser usuario) {
        return new SessionResponse(usuario.getSubject());
    }

    record SessionResponse(
            @Schema(description = "Identificador do GerenteRelacionamento autenticado (claim sub do id_token).",
                    example = "gerente.a") String gerenteId) {
    }
}
