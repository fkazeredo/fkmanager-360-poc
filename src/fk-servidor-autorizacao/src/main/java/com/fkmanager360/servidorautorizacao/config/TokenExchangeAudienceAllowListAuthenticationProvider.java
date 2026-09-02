package com.fkmanager360.servidorautorizacao.config;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenExchangeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A emissao de audience no Token Exchange (RFC 8693) e controle do emissor, nao so do Resource
 * Server (ADR-0015): sem este decorator, o servidor-autorizacao copiaria qualquer
 * resource/audience que o client pedisse direto para o claim {@code aud} emitido -- ver
 * {@link TokenClaimsCustomizerConfig}. O allow-list por client vive na propria
 * {@link RegisteredClient} (setting {@value #ALLOWED_TARGETS_SETTING}), o lugar nativo do Spring
 * Authorization Server para configuracao especifica de um client.
 *
 * <p>Decora (nao substitui) o {@code OAuth2TokenExchangeAuthenticationProvider} padrao no lugar
 * dele, na lista de providers do endpoint de token ({@link AuthorizationServerSecurityConfig}).
 * Decorator, nao um provider adicional na cadeia: o {@code ProviderManager} trata uma
 * {@link AuthenticationException} lancada por um provider como "tente o proximo da lista", nunca
 * como rejeicao definitiva -- prepend-and-throw deixaria o provider padrao, mais adiante na
 * mesma lista, autenticar a mesma requisicao e emitir o token mesmo assim. Delegar por chamada de
 * metodo direta, e nao por outra rodada do ProviderManager, e o unico jeito de garantir que
 * nenhum token seja gerado quando o target nao esta na allow-list.
 */
class TokenExchangeAudienceAllowListAuthenticationProvider implements AuthenticationProvider {

    static final String ALLOWED_TARGETS_SETTING = "fk.token-exchange.allowed-targets";
    private static final String INVALID_TARGET = "invalid_target";

    private final AuthenticationProvider delegate;

    TokenExchangeAudienceAllowListAuthenticationProvider(AuthenticationProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        OAuth2TokenExchangeAuthenticationToken grant = (OAuth2TokenExchangeAuthenticationToken) authentication;

        Set<String> targetsSolicitados = new LinkedHashSet<>();
        targetsSolicitados.addAll(grant.getResources());
        targetsSolicitados.addAll(grant.getAudiences());

        if (!targetsSolicitados.isEmpty()) {
            RegisteredClient registeredClient = registeredClientDoGrant(grant);
            Set<String> targetsPermitidos = registeredClient.getClientSettings().getSetting(ALLOWED_TARGETS_SETTING);

            if (targetsPermitidos == null || !targetsPermitidos.containsAll(targetsSolicitados)) {
                throw new OAuth2AuthenticationException(new OAuth2Error(
                        INVALID_TARGET,
                        "O client %s nao esta autorizado a solicitar token para %s"
                                .formatted(registeredClient.getClientId(), targetsSolicitados),
                        null));
            }
        }

        return delegate.authenticate(authentication);
    }

    private static RegisteredClient registeredClientDoGrant(OAuth2TokenExchangeAuthenticationToken grant) {
        if (grant.getPrincipal() instanceof OAuth2ClientAuthenticationToken clientPrincipal
                && clientPrincipal.getRegisteredClient() != null) {
            return clientPrincipal.getRegisteredClient();
        }
        throw new OAuth2AuthenticationException(new OAuth2Error(INVALID_TARGET, "Client nao identificado", null));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return delegate.supports(authentication);
    }
}
