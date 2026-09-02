package com.fkmanager360.credito.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.TokenExchangeOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.endpoint.RestClientTokenExchangeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * A segunda perna da delegacao encadeada (ADR-0015): quando servico-credito continua a operacao
 * em nome do usuario contra servico-carteira-clientes, ele atua como OAuth client proprio e
 * troca o token que recebeu por outro, com {@code aud = servico-carteira-clientes}. Reutilizar o
 * token destinado a Credito o transformaria em credencial de plataforma, que e exatamente o que
 * a decisao evita.
 *
 * <p><b>A troca reduz capability, nunca amplia.</b> O token que chega aqui carrega
 * {@code credito.leitura} e {@code carteira.leitura}; o token trocado pede somente
 * {@code carteira.leitura}. Nada de novo e introduzido -- e o registro deste client no
 * servidor-autorizacao tem apenas esse scope, de modo que nem formular um pedido mais amplo e
 * possivel. A politica correspondente do lado do emissor recusa qualquer scope que nao esteja no
 * subject token.
 *
 * <p>O subject token e o proprio JWT que autenticou a requisicao: num Resource Server, o
 * principal e um {@code JwtAuthenticationToken} cujo {@code getPrincipal()} ja e um
 * {@code OAuth2Token}, entao o resolver padrao do provider o encontra sem configuracao extra.
 *
 * <p>O cache de token delegado e o {@link OAuth2AuthorizedClientService} padrao, em memoria do
 * processo: ADR-0015 permite reutilizar token delegado dentro da sua curta validade quando
 * subject, audience e scopes sao equivalentes. O que a decisao proibe e o oposto -- emitir
 * tokens longos para nao precisar trocar.
 */
@Configuration
public class TokenExchangeConfig {

    public static final String REGISTRATION_CARTEIRA_CLIENTES = "carteira-clientes-exchange";

    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService,
            @Value("${credito.carteira-clientes.audience}") String carteiraClientesAudience) {

        TokenExchangeOAuth2AuthorizedClientProvider tokenExchangeProvider =
                new TokenExchangeOAuth2AuthorizedClientProvider();

        RestClientTokenExchangeTokenResponseClient tokenExchangeResponseClient =
                new RestClientTokenExchangeTokenResponseClient();
        // Timeout explicito (achado I9 do review de #0002): o RestClient default do Spring
        // Security nao tem timeout algum. Chamada-folha (emitir token e assinatura local, sem
        // downstream propria) -- mesmo orcamento de uma chamada ao CoreLegado.
        tokenExchangeResponseClient.setRestClient(tokenExchangeRestClient());
        // "resource" (RFC 8693) exige URI absoluta; "audience" aceita nome logico -- o mesmo
        // parametro que servidor-autorizacao espera para emitir a aud correta.
        tokenExchangeResponseClient.setParametersCustomizer(
                parameters -> parameters.add("audience", carteiraClientesAudience));
        tokenExchangeProvider.setAccessTokenResponseClient(tokenExchangeResponseClient);

        OAuth2AuthorizedClientProvider providerChain = OAuth2AuthorizedClientProviderBuilder.builder()
                .provider(tokenExchangeProvider)
                .build();

        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(providerChain);
        return manager;
    }

    /**
     * Reproduz deliberadamente o RestClient default que
     * {@code AbstractRestClientOAuth2AccessTokenResponseClient} construiria sozinho -- os
     * conversores {@link FormHttpMessageConverter} (corpo de saida, form-urlencoded) e
     * {@link OAuth2AccessTokenResponseHttpMessageConverter} (corpo de entrada, o formato de
     * resposta do endpoint de token) mais {@link OAuth2ErrorResponseErrorHandler}. Um
     * {@code RestClient.builder().build()} generico NAO sabe desserializar
     * {@code OAuth2AccessTokenResponse} -- so tem Jackson generico --, e {@code setRestClient}
     * substitui o cliente inteiro, nao so o {@code ClientHttpRequestFactory}. Sem esta
     * reproducao, a troca falharia com "accessToken cannot be null" mesmo contra um endpoint de
     * token saudavel.
     */
    private static RestClient tokenExchangeRestClient() {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(3));

        return RestClient.builder()
                .requestFactory(requestFactory)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new FormHttpMessageConverter());
                    converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
    }
}
