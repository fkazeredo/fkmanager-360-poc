package com.fkmanager360.bffgerente.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.TokenExchangeOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.endpoint.RestClientTokenExchangeTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Delegacao por Token Exchange (ADR-0015): quando o BFF precisa continuar a operacao em nome do
 * usuario contra servico-carteira-clientes, troca o token de login por um com
 * {@code aud = servico-carteira-clientes}. Lifecycle (cache, expiracao, renovacao) e inteiramente
 * do {@link OAuth2AuthorizedClientManager} padrao -- nenhum cache proprio de token aqui. O
 * repositorio de authorized clients e o mesmo backing de sessao usado pelo login (Redis via
 * Spring Session), entao o token trocado tambem fica fora do browser.
 */
@Configuration
public class TokenExchangeConfig {

    static final String REGISTRATION_LOGIN = "servidor-autorizacao";
    public static final String REGISTRATION_CARTEIRA_CLIENTES = "carteira-clientes-exchange";

    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientRepository authorizedClientRepository,
            @Value("${bff-gerente.carteira-clientes.audience}") String carteiraClientesAudience) {

        TokenExchangeOAuth2AuthorizedClientProvider tokenExchangeProvider = new TokenExchangeOAuth2AuthorizedClientProvider();
        tokenExchangeProvider.setSubjectTokenResolver(
                context -> resolveLoginToken(context.getPrincipal(), authorizedClientRepository));

        RestClientTokenExchangeTokenResponseClient tokenExchangeResponseClient = new RestClientTokenExchangeTokenResponseClient();
        // "resource" (RFC 8693) exige URI absoluta; "audience" aceita nome logico -- o mesmo
        // parametro que servidor-autorizacao (F4) espera para emitir a aud correta.
        tokenExchangeResponseClient.setParametersCustomizer(parameters -> parameters.add("audience", carteiraClientesAudience));
        tokenExchangeProvider.setAccessTokenResponseClient(tokenExchangeResponseClient);

        OAuth2AuthorizedClientProvider providerChain = OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken()
                .provider(tokenExchangeProvider)
                .build();

        DefaultOAuth2AuthorizedClientManager manager =
                new DefaultOAuth2AuthorizedClientManager(clientRegistrationRepository, authorizedClientRepository);
        manager.setAuthorizedClientProvider(providerChain);
        return manager;
    }

    private static OAuth2Token resolveLoginToken(
            org.springframework.security.core.Authentication principal, OAuth2AuthorizedClientRepository repository) {

        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        OAuth2AuthorizedClient loginClient = repository.loadAuthorizedClient(REGISTRATION_LOGIN, principal, request);
        return loginClient == null ? null : loginClient.getAccessToken();
    }
}
