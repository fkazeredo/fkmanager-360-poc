package com.fkmanager360.bffgerente.config;

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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

/**
 * Delegacao por Token Exchange (ADR-0015): quando o BFF precisa continuar a operacao em nome do
 * usuario contra um Resource Server, troca o token de login por um emitido para aquele destino.
 * Nao existe token de usuario multi-audience circulando pela plataforma -- cada destino tem o
 * seu, e a audience e resolvida a partir do registration usado.
 *
 * <p>Lifecycle (cache, expiracao, renovacao) e inteiramente do
 * {@link OAuth2AuthorizedClientManager} padrao -- nenhum cache proprio de token aqui. O
 * repositorio de authorized clients e o mesmo backing de sessao usado pelo login (Redis via
 * Spring Session), entao os tokens trocados tambem ficam fora do browser.
 */
@Configuration
public class TokenExchangeConfig {

    static final String REGISTRATION_LOGIN = "servidor-autorizacao";
    public static final String REGISTRATION_CARTEIRA_CLIENTES = "carteira-clientes-exchange";
    public static final String REGISTRATION_CREDITO = "credito-exchange";

    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientRepository authorizedClientRepository,
            @Value("${bff-gerente.carteira-clientes.audience}") String carteiraClientesAudience,
            @Value("${bff-gerente.credito.audience}") String creditoAudience) {

        // A audience deixa de ser um valor unico e passa a ser resolvida por registration: com
        // dois destinos, um customizer fixo mandaria a audience errada para um deles -- e o token
        // seria recusado la na frente, por um erro cuja causa nao esta onde ele aparece.
        Map<String, String> audiencePorRegistration = Map.of(
                REGISTRATION_CARTEIRA_CLIENTES, carteiraClientesAudience,
                REGISTRATION_CREDITO, creditoAudience);

        TokenExchangeOAuth2AuthorizedClientProvider tokenExchangeProvider = new TokenExchangeOAuth2AuthorizedClientProvider();
        tokenExchangeProvider.setSubjectTokenResolver(
                context -> resolveLoginToken(context.getPrincipal(), authorizedClientRepository));

        RestClientTokenExchangeTokenResponseClient tokenExchangeResponseClient = new RestClientTokenExchangeTokenResponseClient();
        // addParametersConverter, e nao setParametersCustomizer: so o converter recebe o grant
        // request, e sem ele nao ha como saber para qual destino esta troca e. "resource"
        // (RFC 8693) exige URI absoluta; "audience" aceita nome logico -- o mesmo parametro que
        // servidor-autorizacao espera para emitir a aud correta.
        tokenExchangeResponseClient.addParametersConverter(grantRequest -> {
            String audience = audiencePorRegistration.get(grantRequest.getClientRegistration().getRegistrationId());
            if (audience == null) {
                return null;
            }
            MultiValueMap<String, String> parametros = new LinkedMultiValueMap<>();
            parametros.add("audience", audience);
            return parametros;
        });
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
