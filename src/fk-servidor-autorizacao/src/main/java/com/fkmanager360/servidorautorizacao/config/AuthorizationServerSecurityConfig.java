package com.fkmanager360.servidorautorizacao.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenExchangeAuthenticationProvider;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Set;

/**
 * Replica, campo a campo, os dois {@link SecurityFilterChain} que
 * {@code spring-boot-starter-oauth2-authorization-server} configura sozinho (confirmado por
 * decompilacao de {@code OAuth2AuthorizationServerWebSecurityConfiguration} -- essa
 * autoconfiguracao inteira e {@code @ConditionalOnDefaultWebSecurity}: definir qualquer
 * {@link SecurityFilterChain} na aplicacao desliga as duas, nao so uma). Existe so para acrescentar
 * {@link TokenExchangePolicyAuthenticationProvider} ao endpoint de token, no lugar do
 * {@code OAuth2TokenExchangeAuthenticationProvider} padrao -- esse e o unico ponto de extensao
 * nativo do Spring Authorization Server 7.1 para validar target e scope de um Token Exchange
 * antes da emissao (ADR-0015). Nenhum outro comportamento muda.
 */
@Configuration
public class AuthorizationServerSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http, JwtDecoder ownJwtDecoder)
            throws Exception {
        http.oauth2AuthorizationServer(authorizationServer -> {
            http.securityMatcher(authorizationServer.getEndpointsMatcher());
            authorizationServer.oidc(Customizer.withDefaults());
            // Decora o provider padrao de Token Exchange no proprio lugar dele na lista, em vez
            // de acrescentar mais um: ver o javadoc de TokenExchangePolicyAuthenticationProvider
            // para por que um provider extra que so lanca excecao nao basta aqui.
            authorizationServer.tokenEndpoint(tokenEndpoint -> tokenEndpoint.authenticationProviders(providers -> {
                for (int i = 0; i < providers.size(); i++) {
                    if (providers.get(i) instanceof OAuth2TokenExchangeAuthenticationProvider tokenExchangeProvider) {
                        providers.set(i, new TokenExchangePolicyAuthenticationProvider(tokenExchangeProvider, ownJwtDecoder));
                    }
                }
            }));
        });
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());
        http.oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));
        http.exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"), htmlRequestMatcher()));

        return http.build();
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 5)
    SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        // loginPage("/login") aponta para LoginPageController (pagina no design system da
        // plataforma, ticket #0008) no MESMO caminho da pagina que o Spring Security gerava --
        // nada muda para o fluxo OIDC nem para o nginx, so a renderizacao. permitAll() libera o
        // GET da pagina e o POST de processamento para quem ainda nao tem sessao.
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .formLogin(login -> login.loginPage("/login").permitAll());

        return http.build();
    }

    private static RequestMatcher htmlRequestMatcher() {
        MediaTypeRequestMatcher matcher = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
        matcher.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        return matcher;
    }
}
