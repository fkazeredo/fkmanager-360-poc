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
    private static final String CREDITO_CLIENT_ID = "servico-credito";
    private static final String CREDITO_CLIENT_SECRET = "troque-este-client-secret";

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
                .queryParam("scope", "openid carteira.leitura credito.leitura")
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

    /**
     * ADR-0015: audience-restriction e controle do emissor, nao so do Resource Server. Sem o
     * allow-list de {@link com.fkmanager360.servidorautorizacao.config.RegisteredClientsConfig},
     * este pedido teria devolvido 200 com a aud pedida -- o alvo aqui e um servico que existe no
     * mapa de contextos e nao neste slice, exatamente o tipo de destino que um client comprometido
     * tentaria alcancar.
     */
    @Test
    void tokenExchange_paraTargetNaoAutorizado_eRecusadoComInvalidTarget_semEmitirToken() throws Exception {
        String accessToken = tokenDeLoginDoGerente();

        MvcResult exchangeResult = mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.AUTHORIZATION, basicAuthHeader())
                        .param("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                        .param("subject_token", accessToken)
                        .param("subject_token_type", "urn:ietf:params:oauth:token-type:access_token")
                        .param("audience", "servico-risco"))
                .andExpect(status().isBadRequest())
                .andReturn();

        String corpo = exchangeResult.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(corpo, "$.error")).isEqualTo("invalid_target");
        assertThat(corpo).doesNotContain("access_token");
    }

    // --- A cadeia de delegacao do #0002 -----------------------------------------------------

    @Test
    void tokenExchange_doBffParaCredito_emiteTokenComAsDuasCapacidadesQueACadeiaExige() throws Exception {
        String tokenParaCredito = trocarPor(basicAuthHeader(), tokenDeLoginDoGerente(),
                "servico-credito", "credito.leitura carteira.leitura");

        JWTClaimsSet claims = JWTParser.parse(tokenParaCredito).getJWTClaimsSet();

        assertThat(claims.getAudience()).containsExactly("servico-credito");
        assertThat((List<String>) claims.getClaim("scope"))
                .containsExactlyInAnyOrder("credito.leitura", "carteira.leitura");
        assertThat(claims.getSubject()).isEqualTo("gerente.a");
    }

    /**
     * A segunda perna da cadeia (AC21): servico-credito continua a operacao em nome do usuario e
     * troca o token que recebeu por um para servico-carteira-clientes -- <b>estreitando</b>
     * capability, porque credito.leitura nao tem sentido algum no destino.
     */
    @Test
    void tokenExchange_encadeado_deCreditoParaCarteira_reduzScopeEMantemOSujeito() throws Exception {
        String tokenParaCredito = trocarPor(basicAuthHeader(), tokenDeLoginDoGerente(),
                "servico-credito", "credito.leitura carteira.leitura");

        String tokenParaCarteira = trocarPor(basicAuthHeaderDeCredito(), tokenParaCredito,
                "servico-carteira-clientes", "carteira.leitura");

        JWTClaimsSet claims = JWTParser.parse(tokenParaCarteira).getJWTClaimsSet();

        assertThat(claims.getAudience()).containsExactly("servico-carteira-clientes");
        assertThat((List<String>) claims.getClaim("scope")).containsExactly("carteira.leitura");
        assertThat(claims.getSubject())
                .as("delegacao preserva o sujeito: a operacao continua sendo do gerente")
                .isEqualTo("gerente.a");
        assertThat((List<String>) claims.getClaim("papeis")).containsExactly("GERENTE_RELACIONAMENTO");
    }

    @Test
    void tokenExchange_deCreditoParaUmTargetForaDaSuaAllowList_eRecusado() throws Exception {
        String tokenParaCredito = trocarPor(basicAuthHeader(), tokenDeLoginDoGerente(),
                "servico-credito", "credito.leitura carteira.leitura");

        MvcResult resultado = mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.AUTHORIZATION, basicAuthHeaderDeCredito())
                        .param("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                        .param("subject_token", tokenParaCredito)
                        .param("subject_token_type", "urn:ietf:params:oauth:token-type:access_token")
                        .param("audience", "servico-credito"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat((String) JsonPath.read(resultado.getResponse().getContentAsString(), "$.error"))
                .isEqualTo("invalid_target");
    }

    // --- Nao-amplificacao de privilegio ------------------------------------------------------

    /**
     * O teste decisivo da politica de scope. O client aqui e o proprio bff-gerente, que tem
     * {@code credito.leitura} registrado <b>e</b> servico-credito na allow-list de targets --
     * entao target e registro do client passam. O que recusa e a unica regra que sobra: o
     * <b>subject token</b> apresentado so tem {@code carteira.leitura}, e uma troca nao pode
     * inventar capability que o token original nao carregava.
     *
     * <p>Sem esta regra, uma cadeia de delegacao seria uma escada de privilegio: cada perna
     * poderia pedir mais do que a anterior tinha.
     */
    @Test
    void tokenExchange_pedindoScopeQueOSubjectTokenNaoTem_eRecusadoComInvalidScope_semEmitirToken() throws Exception {
        String tokenSomenteDeCarteira = trocarPor(basicAuthHeader(), tokenDeLoginDoGerente(),
                "servico-carteira-clientes", "carteira.leitura");

        MvcResult resultado = mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.AUTHORIZATION, basicAuthHeader())
                        .param("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                        .param("subject_token", tokenSomenteDeCarteira)
                        .param("subject_token_type", "urn:ietf:params:oauth:token-type:access_token")
                        .param("audience", "servico-credito")
                        .param("scope", "credito.leitura"))
                .andExpect(status().isBadRequest())
                .andReturn();

        String corpo = resultado.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(corpo, "$.error")).isEqualTo("invalid_scope");
        assertThat(corpo).doesNotContain("access_token");
    }

    @Test
    void tokenExchange_deCreditoPedindoScopeQueEleNaoTemRegistrado_eRecusado() throws Exception {
        String tokenParaCredito = trocarPor(basicAuthHeader(), tokenDeLoginDoGerente(),
                "servico-credito", "credito.leitura carteira.leitura");

        // servico-credito tem um unico scope registrado (carteira.leitura). Mesmo carregando
        // credito.leitura no subject token, ele nao pode pedi-lo: as duas verificacoes se somam.
        MvcResult resultado = mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.AUTHORIZATION, basicAuthHeaderDeCredito())
                        .param("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                        .param("subject_token", tokenParaCredito)
                        .param("subject_token_type", "urn:ietf:params:oauth:token-type:access_token")
                        .param("audience", "servico-carteira-clientes")
                        .param("scope", "credito.leitura"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(resultado.getResponse().getContentAsString()).doesNotContain("access_token");
    }

    // --- Helpers da cadeia -------------------------------------------------------------------

    private String tokenDeLoginDoGerente() throws Exception {
        String codeVerifier = gerarCodeVerifier();
        String code = obterCodigoDeAutorizacao(desafioS256(codeVerifier));

        MvcResult resultado = mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.AUTHORIZATION, basicAuthHeader())
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("code_verifier", codeVerifier))
                .andExpect(status().isOk())
                .andReturn();

        return JsonPath.read(resultado.getResponse().getContentAsString(), "$.access_token");
    }

    private String trocarPor(String basicAuth, String subjectToken, String audience, String scope) throws Exception {
        MvcResult resultado = mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.AUTHORIZATION, basicAuth)
                        .param("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                        .param("subject_token", subjectToken)
                        .param("subject_token_type", "urn:ietf:params:oauth:token-type:access_token")
                        .param("audience", audience)
                        .param("scope", scope))
                .andExpect(status().isOk())
                .andReturn();

        return JsonPath.read(resultado.getResponse().getContentAsString(), "$.access_token");
    }

    private static String basicAuthHeaderDeCredito() {
        String credenciais = CREDITO_CLIENT_ID + ":" + CREDITO_CLIENT_SECRET;
        return "Basic " + Base64.getEncoder().encodeToString(credenciais.getBytes(StandardCharsets.UTF_8));
    }
}
