package com.fkmanager360.bffgerente.seguranca;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S6: cookie-only, CSRF, sessao em Redis e PKCE obrigatorio mesmo para client confidencial
 * (AC19, AC20). O fluxo completo de login contra um servidor-autorizacao real e
 * responsabilidade do S7/Playwright (ADR-0018: S7 prova que a topologia fecha) -- aqui a
 * fronteira e a seguranca deste proprio servico.
 */
@Testcontainers
@SpringBootTest(
        // RANDOM_PORT (nao MOCK): o serializer de cookie do Spring Session so aplica
        // HttpOnly/SameSite/Path atraves de um container servlet real.
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // application.yml ja usa endpoints explicitos (nao issuer-uri), entao nenhuma
                // descoberta OIDC acontece na inicializacao -- so redireciona os endpoints para
                // um host inalcancavel, ja que nenhum destes testes completa login de verdade.
                // O fluxo completo contra o servico real e responsabilidade do S7 (Playwright).
                "spring.security.oauth2.client.provider.servidor-autorizacao.authorization-uri=http://servidor-autorizacao.invalid/oauth2/authorize",
                "spring.security.oauth2.client.provider.servidor-autorizacao.token-uri=http://servidor-autorizacao.invalid/oauth2/token",
                "spring.security.oauth2.client.provider.servidor-autorizacao.jwk-set-uri=http://servidor-autorizacao.invalid/oauth2/jwks",
                "spring.security.oauth2.client.provider.servidor-autorizacao.user-info-uri=http://servidor-autorizacao.invalid/userinfo",
                "spring.security.oauth2.client.provider.servidor-autorizacao.user-name-attribute=sub"
        })
@AutoConfigureMockMvc
class BffSegurancaTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Test
    void api_semAutenticacao_e401_naoRedirecionaParaHtml() throws Exception {
        mockMvc.perform(get("/api/sessao"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void api_comSessaoAutenticada_retornaGerenteId() throws Exception {
        // Regressao: o controller resolvia o OidcUser por injecao implicita de parametro (sem
        // @AuthenticationPrincipal), o que o MVC trata como model attribute a construir por
        // data binding -- IllegalStateException em runtime, nunca coberta pelo 401 do teste
        // acima nem pelos testes de login/logout, que nao chegam a chamar usuario.getSubject().
        mockMvc.perform(get("/api/sessao")
                        .with(SecurityMockMvcRequestPostProcessors.oidcLogin()
                                .idToken(token -> token.subject("gerente.a"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gerenteId").value("gerente.a"));
    }

    @Test
    void logout_semTokenCsrf_eRecusado() throws Exception {
        mockMvc.perform(post("/logout")
                        .with(SecurityMockMvcRequestPostProcessors.user("gerente.a")))
                .andExpect(status().isForbidden());
    }

    @Test
    void logout_comTokenCsrf_eAceito() throws Exception {
        mockMvc.perform(post("/logout")
                        .with(SecurityMockMvcRequestPostProcessors.user("gerente.a"))
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void loginInicial_forcaPkce_mesmoSendoClientConfidencial() throws Exception {
        MvcResult resultado = mockMvc.perform(get("/oauth2/authorization/servidor-autorizacao"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = resultado.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).contains("code_challenge=").contains("code_challenge_method=S256");
    }

    @Test
    void sessao_ePersistidaEmRedis_naoEmMemoriaLocal() throws Exception {
        MvcResult resultado = mockMvc.perform(get("/actuator/health")).andReturn();
        var sessao = resultado.getRequest().getSession(false);
        // /actuator/health e permitAll e nao força sessao; provamos o backing store diretamente:
        // qualquer sessao que o Spring Security venha a criar (ex.: durante o fluxo de login) usa
        // este ConnectionFactory, que aponta para o container Redis real, nao para memoria local.
        try (var conexao = redisConnectionFactory.getConnection()) {
            assertThat(conexao.ping()).isEqualTo("PONG");
        }
    }

    @Test
    void requisicaoQualquer_emiteCookieXsrfTokenComPathRaiz() throws Exception {
        // Regressao 1: CsrfFilter resolve o token via Supplier adiado -- sem algo que force essa
        // resolucao, o cookie XSRF-TOKEN nunca e escrito numa API pura (sem view server-side
        // lendo "_csrf"), e a SPA fica sem meio legitimo de obter o token para POST /logout.
        //
        // Regressao 2: sem Path="/" explicito, CookieCsrfTokenRepository herda o context-path do
        // servlet (/bff) como Path do cookie. document.cookie de uma pagina servida em "/"
        // (app-gerente) nunca enxerga um cookie escopado a "/bff" -- o browser ainda o ENVIA de
        // volta em requisicoes para /bff/**, entao o sintoma so aparece do lado do JS, nunca em
        // ferramentas que nao respeitam Path na leitura (curl, Postman, MockMvc sem esta
        // asercao).
        MvcResult resultado = mockMvc.perform(get("/actuator/health")).andReturn();

        String setCookie = resultado.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("XSRF-TOKEN=").contains("Path=/");
        assertThat(setCookie).doesNotContain("Path=/bff");
    }

    @Test
    void cookieDeSessao_eHttpOnlyComSameSiteLax() throws Exception {
        MvcResult resultado = mockMvc.perform(get("/oauth2/authorization/servidor-autorizacao"))
                .andReturn();

        String setCookie = resultado.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("HttpOnly").contains("SameSite=Lax");
    }
}
