package com.fkmanager360.bffgerente.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * bff-gerente e OAuth2 confidential client conduzindo Authorization Code + PKCE + OIDC contra
 * servidor-autorizacao (ADR-0015). O browser recebe apenas o cookie de sessao -- access token,
 * refresh token e client secret nunca chegam ao Angular. A sessao vive em Redis via Spring
 * Session (configuracao em application.yml), para que o BFF escale sem sticky session e
 * sobreviva a restart da instancia que a originou (AC20).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            ClientRegistrationRepository clientRegistrationRepository,
            @Value("${bff-gerente.app-public-url}") String appPublicUrl,
            @Value("${bff-gerente.end-session-endpoint}") String endSessionEndpoint) throws Exception {
        OAuth2AuthorizationRequestResolver pkceRequiredResolver = mandatoryPkce(clientRegistrationRepository);

        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Documentacao, nao dado de negocio (ADR-0019): o contrato gerado
                        // (src/fk-bff-gerente/openapi.yaml) e regenerado a partir destes mesmos
                        // paths -- exigir sessao aqui so quebraria a propria regeneracao.
                        .requestMatchers("/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(oauth2 -> oauth2
                        // Sem isto, o alvo padrao pos-login e "/" -- e como bff-gerente tem
                        // context-path /bff, o container resolve um redirect relativo comecando
                        // com "/" contra o PROPRIO context path (Servlet spec), pousando o
                        // browser em "/bff/", que nao tem controller nenhum (404). A pagina de
                        // verdade e servida por app-gerente/nginx na origem publica.
                        .defaultSuccessUrl(appPublicUrl, true)
                        .authorizationEndpoint(
                                endpoint -> endpoint.authorizationRequestResolver(pkceRequiredResolver)))
                // /api/** e chamado por fetch/XHR do Angular: um 401 permite a SPA decidir a
                // navegacao (ela mesma redireciona o browser para o login); um 302 devolveria
                // HTML de login para dentro de uma chamada de API, que a SPA nao sabe tratar.
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessHandler(rpInitiatedLogoutHandler(appPublicUrl, endSessionEndpoint)))
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
                .addFilterAfter(new ForceCsrfResolutionFilter(), BasicAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Sem este bean, o Spring Security nao encontra nenhum {@link OAuth2AuthorizedClientRepository}
     * nem {@code OAuth2AuthorizedClientService} explicito no contexto e cai no proprio default
     * (verificado em {@code OAuth2ClientConfigurerUtils}): um {@code InMemoryOAuth2AuthorizedClientService},
     * vivo só no processo. O login (via {@code SPRING_SECURITY_CONTEXT}) sobrevive a restart
     * porque o Spring Session ja serializa isso; o access/refresh token do login e o token trocado
     * para servico-carteira-clientes (ADR-0015) nao sobreviveriam -- exatamente o que o AC20 exige.
     * {@link HttpSessionOAuth2AuthorizedClientRepository} guarda como atributo de sessao, entao
     * segue o mesmo backing store Redis que o resto da sessao (application.yml).
     */
    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    /**
     * RP-Initiated Logout (OIDC): encerrar so a sessao local do BFF deixava a sessao SSO viva no
     * servidor-autorizacao -- "Sair" seguido de "Entrar" logava de volta sem pedir senha. O
     * handler devolve, num corpo JSON, a URL publica de end-session
     * ({@code /connect/logout?id_token_hint=...&post_logout_redirect_uri=...}) para a SPA navegar
     * ate la: o logout OIDC exige navegacao real do browser (o cookie de sessao do
     * servidor-autorizacao viaja nela), entao um redirect 302 nesta resposta XHR nao serviria --
     * fetch seguiria o redirect por baixo dos panos, sem trocar a pagina.
     *
     * <p>A URL usa a origem PUBLICA (nginx), como {@code authorization-uri} -- e o browser quem
     * visita. O {@code post_logout_redirect_uri} precisa casar exatamente com o valor registrado
     * no client ({@code RegisteredClientsConfig}), por isso reusa {@code app-public-url}.
     *
     * <p>Sem OidcUser na autenticacao (sessao ja expirada, por exemplo) nao ha id_token_hint a
     * enviar: o corpo devolve a origem publica direta e o desfecho e o mesmo de antes -- apenas a
     * sessao local morta.
     */
    private static LogoutSuccessHandler rpInitiatedLogoutHandler(String appPublicUrl, String endSessionEndpoint) {
        return (HttpServletRequest request, HttpServletResponse response, Authentication authentication) -> {
            String redirectUrl = appPublicUrl;
            if (authentication != null && authentication.getPrincipal() instanceof OidcUser oidcUser) {
                redirectUrl = UriComponentsBuilder.fromUriString(endSessionEndpoint)
                        .queryParam("id_token_hint", oidcUser.getIdToken().getTokenValue())
                        .queryParam("post_logout_redirect_uri", appPublicUrl)
                        .build()
                        .toUriString();
            }
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"redirectUrl\":\"" + redirectUrl + "\"}");
        };
    }

    private static CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        return repository;
    }

    private static final class ForceCsrfResolutionFilter extends OncePerRequestFilter {
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
    private static OAuth2AuthorizationRequestResolver mandatoryPkce(ClientRegistrationRepository clientRegistrationRepository) {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository, OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
    }
}
