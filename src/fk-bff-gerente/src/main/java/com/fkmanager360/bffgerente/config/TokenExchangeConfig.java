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
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
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
 *
 * <h2>Orcamento de timeout (achado I9 do review de #0002)</h2>
 *
 * <p>Cada chamada de rede desta plataforma tem um orcamento declarado -- soma de connect e read
 * timeout --, e cada consumidor precisa exceder o orcamento declarado do seu destino imediato,
 * com margem fixa de 3s, para nunca desistir de uma cadeia que o proprio destino ainda vai
 * concluir dentro do tempo que ELE promete:
 *
 * <pre>
 * CoreLegado (leaf)                                     2s + 3s = 5s
 * Token Exchange / servidor-autorizacao (leaf)          2s + 3s = 5s
 * CarteiraClientes /contas, /direito-de-atendimento     1x CoreLegado                = 5s
 * CarteiraClientes /contexto-atendimento                2x CoreLegado (sequencial)   = 10s
 * Credito -&gt; CarteiraClientes (endpoint estreito)       5s (declarado) + 3s margem   = 8s  read
 * BFF -&gt; CarteiraClientes (usa o pior caso, contexto)   10s (declarado) + 3s margem  = 13s read
 * BFF -&gt; Credito (declarado: exchange + carteira + core = 5s+5s+5s = 15s)            = 18s read
 * </pre>
 *
 * <p>Os valores configurados aqui e nos RestClient de {@link CarteiraClientesRestClientConfig} e
 * {@link CreditoRestClientConfig} implementam essa tabela; mudar um orcamento declarado sem
 * revisar os consumidores que dependem dele quebra a garantia de "outer &gt; inner".
 */
@Configuration
public class TokenExchangeConfig {

    static final String REGISTRATION_LOGIN = "servidor-autorizacao";
    public static final String REGISTRATION_CARTEIRA_CLIENTES = "carteira-clientes-exchange";
    /** Delegacao para o GET do limite vigente (scope {@code credito.leitura}). */
    public static final String REGISTRATION_CREDITO_LEITURA = "credito-leitura-exchange";
    /**
     * Delegacao para o POST de submissao (scope {@code credito.escrita}) -- plano #0003, secao 9,
     * "Least privilege por operacao". Mesmo destino/audience de {@link #REGISTRATION_CREDITO_LEITURA}
     * ({@code servico-credito}); so o scope pedido muda, resolvido por registration em
     * {@code application.yml}.
     */
    public static final String REGISTRATION_CREDITO_ESCRITA = "credito-escrita-exchange";

    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientRepository authorizedClientRepository,
            @Value("${bff-gerente.carteira-clientes.audience}") String carteiraClientesAudience,
            @Value("${bff-gerente.credito.audience}") String creditoAudience) {

        // A audience deixa de ser um valor unico e passa a ser resolvida por registration: com
        // tres destinos (dois deles para o MESMO servico-credito, um por scope), um customizer
        // fixo mandaria a audience errada para um deles -- e o token seria recusado la na frente,
        // por um erro cuja causa nao esta onde ele aparece.
        Map<String, String> audiencePorRegistration = Map.of(
                REGISTRATION_CARTEIRA_CLIENTES, carteiraClientesAudience,
                REGISTRATION_CREDITO_LEITURA, creditoAudience,
                REGISTRATION_CREDITO_ESCRITA, creditoAudience);

        TokenExchangeOAuth2AuthorizedClientProvider tokenExchangeProvider = new TokenExchangeOAuth2AuthorizedClientProvider();
        tokenExchangeProvider.setSubjectTokenResolver(
                context -> resolveLoginToken(context.getPrincipal(), authorizedClientRepository));

        RestClientTokenExchangeTokenResponseClient tokenExchangeResponseClient = new RestClientTokenExchangeTokenResponseClient();
        // Timeout explicito (achado I9 do review de #0002): sem isto, esta chamada usava o
        // RestClient default do Spring Security, sem timeout algum -- um servidor-autorizacao
        // que aceita a conexao mas nunca responde travaria a requisicao inteira indefinidamente,
        // antes mesmo de qualquer timeout configurado em qualquer outro cliente ter chance de
        // agir. E chamada local, sem downstream propria (emitir um token e assinatura, nao
        // encadeamento) -- o mesmo orcamento-folha de uma chamada ao CoreLegado.
        tokenExchangeResponseClient.setRestClient(tokenExchangeRestClient());
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

    private static OAuth2Token resolveLoginToken(
            org.springframework.security.core.Authentication principal, OAuth2AuthorizedClientRepository repository) {

        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        OAuth2AuthorizedClient loginClient = repository.loadAuthorizedClient(REGISTRATION_LOGIN, principal, request);
        return loginClient == null ? null : loginClient.getAccessToken();
    }
}
