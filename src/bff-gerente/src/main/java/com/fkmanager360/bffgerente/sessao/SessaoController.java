package com.fkmanager360.bffgerente.sessao;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * O suficiente para o Angular saber quem esta logado sem inspecionar o cookie -- que ele nao
 * consegue e nem deve (HttpOnly). Nenhum token atravessa esta fronteira.
 */
@RestController
public class SessaoController {

    @GetMapping("/api/sessao")
    SessaoResponse sessaoAtual(@AuthenticationPrincipal OidcUser usuario) {
        return new SessaoResponse(usuario.getSubject());
    }

    record SessaoResponse(String gerenteId) {
    }
}
