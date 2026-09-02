package com.fkmanager360.servidorautorizacao;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import java.time.Duration;

/**
 * O unico client registrado neste ticket: {@code bff-gerente}, confidencial, conduzindo
 * Authorization Code + PKCE + OIDC e, mais tarde na mesma requisicao, Token Exchange para chamar
 * servico-carteira-clientes em nome do usuario (ADR-0015). Scopes sao capacidades grossas -- nunca
 * politica de negocio.
 */
@Configuration
public class ClientesRegistradosConfig {

    @Bean
    RegisteredClientRepository registeredClientRepository(
            PasswordEncoder passwordEncoder,
            @Value("${servidor-autorizacao.bff-client.client-id}") String clientId,
            @Value("${servidor-autorizacao.bff-client.client-secret}") String clientSecret,
            @Value("${servidor-autorizacao.bff-client.redirect-uri}") String redirectUri,
            @Value("${servidor-autorizacao.bff-client.post-logout-redirect-uri}") String postLogoutRedirectUri) {

        RegisteredClient bffGerente = RegisteredClient.withId("bff-gerente-client")
                .clientId(clientId)
                .clientSecret(passwordEncoder.encode(clientSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
                .redirectUri(redirectUri)
                .postLogoutRedirectUri(postLogoutRedirectUri)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope("carteira.leitura")
                .clientSettings(ClientSettings.builder()
                        // PKCE obrigatorio: sem isto, o ticket nao terminou.
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(5))
                        .refreshTokenTimeToLive(Duration.ofHours(12))
                        .reuseRefreshTokens(false)
                        .build())
                .build();

        return new InMemoryRegisteredClientRepository(bffGerente);
    }
}
