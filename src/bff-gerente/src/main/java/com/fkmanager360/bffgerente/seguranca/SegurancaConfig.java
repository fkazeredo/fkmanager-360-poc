package com.fkmanager360.bffgerente.seguranca;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * bff-gerente e OAuth2 confidential client conduzindo Authorization Code + PKCE + OIDC contra
 * servidor-autorizacao (ADR-0015). O browser recebe apenas o cookie de sessao -- access token,
 * refresh token e client secret nunca chegam ao Angular. A sessao vive em Redis via Spring
 * Session (configuracao em application.yml), para que o BFF escale sem sticky session e
 * sobreviva a restart da instancia que a originou (AC20).
 */
@Configuration
public class SegurancaConfig {

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            ClientRegistrationRepository clientRegistrationRepository,
            @Value("${bff-gerente.app-url-publica}") String appUrlPublica) throws Exception {
        OAuth2AuthorizationRequestResolver resolverComPkce = pkceObrigatorio(clientRegistrationRepository);

        http
                .authorizeHttpRequests(autorizacao -> autorizacao
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(oauth2 -> oauth2
                        // Sem isto, o alvo padrao pos-login e "/" -- e como bff-gerente tem
                        // context-path /bff, o container resolve um redirect relativo comecando
                        // com "/" contra o PROPRIO context path (Servlet spec), pousando o
                        // browser em "/bff/", que nao tem controller nenhum (404). A pagina de
                        // verdade e servida por app-gerente/nginx na origem publica.
                        .defaultSuccessUrl(appUrlPublica, true)
                        .authorizationEndpoint(
                                endpoint -> endpoint.authorizationRequestResolver(resolverComPkce)))
                // /api/** e chamado por fetch/XHR do Angular: um 401 permite a SPA decidir a
                // navegacao (ela mesma redireciona o browser para o login); um 302 devolveria
                // HTML de login para dentro de uma chamada de API, que a SPA nao sabe tratar.
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.NO_CONTENT.value())))
                .csrf(csrf -> csrf
                        // Convencao do Angular: le o cookie XSRF-TOKEN (nao HttpOnly, para o JS
                        // conseguir ler) e devolve como header X-XSRF-TOKEN. Path explicito "/":
                        // sem isto, CookieCsrfTokenRepository herda o context-path do servlet
                        // (/bff) como Path do cookie, e document.cookie de uma pagina servida em
                        // "/" (app-gerente) nunca enxerga um cookie escopado a "/bff" -- o
                        // browser AINDA o envia de volta em requisicoes para /bff/**, entao o
                        // sintoma so aparece do lado do JS, nunca em curl/Postman.
                        .csrfTokenRepository(csrfTokenRepository())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                // O CsrfFilter resolve o token so sob demanda (Supplier adiado, para nao pagar o
                // custo em toda requisicao). Numa API pura, sem view server-side que leia "_csrf",
                // essa resolucao nunca aconteceria e o cookie XSRF-TOKEN nunca seria escrito --
                // deixando a SPA sem meio legitimo de completar POST /logout. Este filtro forca a
                // resolucao em toda requisicao, exatamente como a documentacao do Spring Security
                // recomenda para SPAs.
                .addFilterAfter(new ForcarResolucaoCsrfFilter(), BasicAuthenticationFilter.class);

        return http.build();
    }

    private static CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repositorio = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repositorio.setCookiePath("/");
        return repositorio;
    }

    private static final class ForcarResolucaoCsrfFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }

    /**
     * PKCE e obrigatorio mesmo sendo o bff-gerente um client confidencial: "sem PKCE, o ticket
     * nao terminou" (ticket #0001). O resolver padrao do Spring Security so aplica PKCE
     * automaticamente para clients publicos -- aqui ele e forcado explicitamente.
     */
    private static OAuth2AuthorizationRequestResolver pkceObrigatorio(ClientRegistrationRepository clientRegistrationRepository) {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository, OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
    }
}
