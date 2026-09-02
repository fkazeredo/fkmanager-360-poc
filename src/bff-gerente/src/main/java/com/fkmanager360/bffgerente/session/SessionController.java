package com.fkmanager360.bffgerente.session;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * O suficiente para o Angular saber quem esta logado sem inspecionar o cookie -- que ele nao
 * consegue e nem deve (HttpOnly). Nenhum token atravessa esta fronteira.
 */
@RestController
public class SessionController {

    // Path publico, ja contrato exercitado por Angular/Playwright -- nao muda so porque a classe
    // Java virou tecnica em ingles.
    @GetMapping("/api/sessao")
    SessionResponse currentSession(@AuthenticationPrincipal OidcUser usuario) {
        return new SessionResponse(usuario.getSubject());
    }

    record SessionResponse(String gerenteId) {
    }
}
