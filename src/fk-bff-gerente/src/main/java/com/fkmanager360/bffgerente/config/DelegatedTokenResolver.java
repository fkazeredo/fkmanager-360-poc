package com.fkmanager360.bffgerente.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;

/**
 * Obtem, por Token Exchange, o token delegado para um destino especifico (ADR-0015). Extraido
 * porque o BFF passou a falar com dois Resource Servers em #0002: a mesma resolucao repetida em
 * cada controller convidaria a divergirem justo no ponto em que uma divergencia silenciosa
 * significaria mandar o token errado para o destino errado.
 */
@Component
@RequiredArgsConstructor
public class DelegatedTokenResolver {

    private final OAuth2AuthorizedClientManager authorizedClientManager;

    public String tokenPara(
            String registrationId,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {

        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId(registrationId)
                .principal(authentication)
                .attribute(HttpServletRequest.class.getName(), request)
                .attribute(HttpServletResponse.class.getName(), response)
                .build();

        OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(authorizeRequest);
        if (authorizedClient == null) {
            throw new IllegalStateException("Nao foi possivel obter token delegado para " + registrationId);
        }
        return authorizedClient.getAccessToken().getTokenValue();
    }
}
