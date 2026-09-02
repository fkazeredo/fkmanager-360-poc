package com.fkmanager360.servidorautorizacao;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S6: PKCE obrigatorio, Authorization Code, OIDC e Token Exchange (AC19, AC21 parcial). O
 * login form em si e codigo do proprio Spring Security, ja testado por ele -- aqui simulamos o
 * gerente ja autenticado (mesma autoridade que o UserDetailsService real concede) e focamos no
 * que e nosso: enforcement de PKCE, claims emitidas e a fronteira de audience do Token Exchange.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AutorizacaoEndToEndTest {

    private static final String CLIENT_ID = "bff-gerente";
    private static final String CLIENT_SECRET = "troque-este-client-secret";
    private static final String REDIRECT_URI = "https://localhost/bff/login/oauth2/code/servidor-autorizacao";

    @Autowired
    private MockMvc mockMvc;

    private static String gerarCodeVerifier() {
        byte[] bytes = new byte[64];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).substring(0, 64);
    }

    private static String desafioS256(String codeVerifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static String basicAuthHeader() {
        String credenciais = CLIENT_ID + ":" + CLIENT_SECRET;
        return "Basic " + Base64.getEncoder().encodeToString(credenciais.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * O filtro de validacao de PKCE do Authorization Server le a query string bruta da
     * requisicao, nao o parameter map do MockMvc -- {@code .param(...)} sozinho fica invisivel
     * para ele. A URL precisa carregar os parametros ja montados.
     */
    private static java.net.URI uriDeAutorizacao(String codeChallenge, String state) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", CLIENT_ID)
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("scope", "openid carteira.leitura")
                .queryParam("state", state);

        if (codeChallenge != null) {
            builder.queryParam("code_challenge", codeChallenge)
                    .queryParam("code_challenge_method", "S256");
        }

        return builder.build().encode().toUri();
    }

    /**
     * OIDC exige {@code auth_time} no id_token, que so existe quando a autenticacao passou pelo
     * fluxo real de login -- {@code .with(user(...))} injeta um principal sem esse metadado e
     * quebra a emissao do id_token. Login real tambem casa com o espirito de ADR-0018: a
     * autenticacao e parte do que se quer provar.
     */
    private org.springframework.mock.web.MockHttpSession autenticarComoGerenteA() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/login")
                        .param("username", "gerente.a")
                        .param("password", "troque-senha-a")
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        var session = (org.springframework.mock.web.MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).as("login deveria estabelecer uma sessao autenticada").isNotNull();
        return session;
    }

    private String obterCodigoDeAutorizacao(String codeChallenge) throws Exception {
        var sessao = autenticarComoGerenteA();

        MvcResult resultado = mockMvc.perform(get(uriDeAutorizacao(codeChallenge, "estado-de-teste"))
                        .session(sessao))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = resultado.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).as("authorize deveria redirecionar com um code").contains("code=");

        return java.net.URLDecoder.decode(location.replaceAll(".*[?&]code=([^&]+).*", "$1"), StandardCharsets.UTF_8);
    }

    @Test
    void fluxoCompleto_authorizationCodeComPkce_emiteTokenComPapeisEScopeCorretos() throws Exception {
        String codeVerifier = gerarCodeVerifier();
        String codeChallenge = desafioS256(codeVerifier);

        String code = obterCodigoDeAutorizacao(codeChallenge);

        MvcResult tokenResult = mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.AUTHORIZATION, basicAuthHeader())
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("code_verifier", codeVerifier))
                .andExpect(status().isOk())
                .andReturn();

        String corpo = tokenResult.getResponse().getContentAsString();
        String accessToken = JsonPath.read(corpo, "$.access_token");
        assertThat(accessToken).isNotBlank();
        assertThat((String) JsonPath.read(corpo, "$.id_token")).isNotBlank();
        assertThat((String) JsonPath.read(corpo, "$.refresh_token")).isNotBlank();

        JWTClaimsSet claims = JWTParser.parse(accessToken).getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo("gerente.a");
        assertThat((List<String>) claims.getClaim("papeis")).containsExactly("GERENTE_RELACIONAMENTO");
        assertThat((List<String>) claims.getClaim("scope")).contains("carteira.leitura");
    }

    @Test
    void token_comCodeVerifierQueNaoBateComOChallenge_eRecusado() throws Exception {
        String code = obterCodigoDeAutorizacao(desafioS256(gerarCodeVerifier()));

        mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.AUTHORIZATION, basicAuthHeader())
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("code_verifier", gerarCodeVerifier()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void authorize_semCodeChallenge_eRecusado() throws Exception {
        mockMvc.perform(get(uriDeAutorizacao(null, UUID.randomUUID().toString()))
                        .with(SecurityMockMvcRequestPostProcessors.user("gerente.a")
                                .authorities(new SimpleGrantedAuthority("ROLE_GERENTE_RELACIONAMENTO"))))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> {
                    String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
                    assertThat(location).as("sem PKCE, o authorize nao pode devolver um code")
                            .doesNotContain("code=")
                            .contains("error=invalid_request")
                            .contains("code_challenge");
                });
    }

    @Test
    void tokenExchange_devolveTokenComAudienceSolicitada_semPerderSubNemPapeis() throws Exception {
        String codeVerifier = gerarCodeVerifier();
        String codeChallenge = desafioS256(codeVerifier);
        String code = obterCodigoDeAutorizacao(codeChallenge);

        MvcResult loginTokenResult = mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.AUTHORIZATION, basicAuthHeader())
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("code_verifier", codeVerifier))
                .andExpect(status().isOk())
                .andReturn();

        String accessToken = JsonPath.read(loginTokenResult.getResponse().getContentAsString(), "$.access_token");

        MvcResult exchangeResult = mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.AUTHORIZATION, basicAuthHeader())
                        .param("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                        .param("subject_token", accessToken)
                        .param("subject_token_type", "urn:ietf:params:oauth:token-type:access_token")
                        // "resource" (RFC 8693) exige URI absoluta; "audience" aceita nome logico
                        // -- servico-carteira-clientes e identidade logica, nao uma URL.
                        .param("audience", "servico-carteira-clientes"))
                .andExpect(status().isOk())
                .andReturn();

        String tokenTrocado = JsonPath.read(exchangeResult.getResponse().getContentAsString(), "$.access_token");
        JWTClaimsSet claims = JWTParser.parse(tokenTrocado).getJWTClaimsSet();

        assertThat(claims.getAudience()).containsExactly("servico-carteira-clientes");
        assertThat(claims.getSubject()).isEqualTo("gerente.a");
        assertThat((List<String>) claims.getClaim("papeis")).containsExactly("GERENTE_RELACIONAMENTO");
    }
}
