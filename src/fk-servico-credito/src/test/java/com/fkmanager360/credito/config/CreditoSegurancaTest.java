package com.fkmanager360.credito.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S6: routing HTTP, JWT, audience, scope, papel, autorizacao de recurso e Token Exchange
 * encadeado. Nao reexamina a traducao da ACL (S4) nem a orquestracao (S2).
 *
 * <p>Dois invariantes sao provados aqui na forma mais literal possivel:
 *
 * <ul>
 *   <li><b>AC23</b> -- 403 vindo de CarteiraClientes, com o stub do CoreLegado registrando
 *       <b>zero</b> requisicoes;</li>
 *   <li><b>AC21</b> -- a troca encadeada pede {@code aud = servico-carteira-clientes} e
 *       {@code scope = carteira.leitura}, e <b>nao</b> pede {@code credito.leitura}: a segunda
 *       perna da cadeia reduz capability, nunca amplia.</li>
 * </ul>
 *
 * <p>Um unico mock HTTP server representa as tres dependencias externas (endpoint de token do
 * servidor-autorizacao, servico-carteira-clientes e simulador-core-legado), distinguidas por
 * path. Nenhum servidor real precisa estar de pe para provar seguranca (ADR-0018).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "credito.security.expected-audience=" + JwtDecoderTestConfig.EXPECTED_AUDIENCE)
@AutoConfigureMockMvc
@Import(JwtDecoderTestConfig.class)
class CreditoSegurancaTest {

    private static final String AUD = JwtDecoderTestConfig.EXPECTED_AUDIENCE;
    private static final String PATH_CONTEXTO = "/clientes/1/contas/10001/contexto-atendimento";
    private static final String PATH_CORE = "/legado/contas/consulta-credito";
    private static final String PATH_TOKEN = "/oauth2/token";
    private static final String ENDPOINT = "/clientes/1/contas/10001/limite-cheque-especial-vigente";

    private static WireMockServer dependenciasExternas;

    @BeforeAll
    static void subirDependenciasExternas() {
        dependenciasExternas = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        dependenciasExternas.start();
    }

    @AfterAll
    static void pararDependenciasExternas() {
        dependenciasExternas.stop();
    }

    /**
     * A porta do mock server so existe depois de ele subir, entao as tres URLs sao registradas
     * dinamicamente. O {@code issuer-uri} do Resource Server continua vazio de proposito: o
     * decoder de teste ja esta declarado, e apontar um issuer faria o contexto tentar descoberta
     * OIDC na inicializacao.
     */
    @DynamicPropertySource
    static void apontarDependenciasParaOMockServer(DynamicPropertyRegistry registry) {
        registry.add("credito.core-legado.base-url", () -> "http://localhost:" + dependenciasExternas.port());
        registry.add("credito.carteira-clientes.base-url", () -> "http://localhost:" + dependenciasExternas.port());
        registry.add("spring.security.oauth2.client.provider.servidor-autorizacao.token-uri",
                () -> "http://localhost:" + dependenciasExternas.port() + PATH_TOKEN);
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void comportamentoPadraoDasDependencias() {
        dependenciasExternas.resetAll();

        // servidor-autorizacao: devolve um token delegado com aud = servico-carteira-clientes.
        dependenciasExternas.stubFor(post(urlEqualTo(PATH_TOKEN)).willReturn(json("""
                {"access_token":"%s","issued_token_type":"urn:ietf:params:oauth:token-type:access_token",
                 "token_type":"Bearer","expires_in":300}
                """.formatted(tokenDelegado()))));

        // CarteiraClientes: concede o atendimento por padrao.
        dependenciasExternas.stubFor(WireMock.get(urlEqualTo(PATH_CONTEXTO)).willReturn(json("""
                {"clienteId":"1","nome":"ANA BEATRIZ SOUZA","cpfMascarado":"***.222.333-**",
                 "conta":{"contaId":"10001","agencia":"0001"}}
                """)));

        // CoreLegado.
        dependenciasExternas.stubFor(post(urlEqualTo(PATH_CORE)).willReturn(json("""
                {"codRet":"000","msgRet":"OK","numCta":"0000010001","vlrLimChqEsp":"000000000500000",
                 "sitCta":"01","codRscCrd":"1","datAtuLim":"20200101"}
                """)));
    }

    /**
     * Cada teste autentica com um {@code sub} proprio: o cache de token delegado do
     * OAuth2AuthorizedClientService e por principal, e reaproveitar o mesmo gerente entre testes
     * faria a segunda troca nao acontecer -- escondendo justamente o que se quer verificar.
     */
    private String tokenDeGerente(String sub) {
        return JwtTestSupport.validToken(sub, AUD, List.of("GERENTE_RELACIONAMENTO"));
    }

    // --- Autenticacao e capacidades ---------------------------------------------------------

    @Test
    void semToken_e401() throws Exception {
        mockMvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenDeOutroResourceServer_eRecusadoPelaValidacaoDeAudience_401() throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .header("Authorization", "Bearer " + JwtTestSupport.tokenWithWrongAudience("gerente.aud")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenExpirado_e401() throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .header("Authorization", "Bearer " + JwtTestSupport.expiredToken("gerente.exp", AUD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void semScopeDeCreditoLeitura_e403() throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .header("Authorization", "Bearer " + JwtTestSupport.tokenWithoutCreditoScope("gerente.scope", AUD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void semPapelGerenteRelacionamento_e403() throws Exception {
        mockMvc.perform(get(ENDPOINT)
                        .header("Authorization", "Bearer " + JwtTestSupport.tokenWithoutManagerRole("gerente.papel", AUD)))
                .andExpect(status().isForbidden());
    }

    // --- AC29 (parcial): o limite vem do Core ------------------------------------------------

    @Test
    void comDireitoDeAtendimento_devolveOLimiteVigenteEmCentavos() throws Exception {
        mockMvc.perform(get(ENDPOINT).header("Authorization", "Bearer " + tokenDeGerente("gerente.ok")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contaId").value("10001"))
                .andExpect(jsonPath("$.limiteChequeEspecialVigente").value(500000))
                .andExpect(jsonPath("$.consultadoEm").exists());
    }

    @Test
    void aRespostaNaoExpoeClassificacaoDeRiscoSituacaoNemDadoCadastral() throws Exception {
        // AC30 e "Apresentacao" da spec: a classificacao e insumo interno da politica e nunca e
        // apresentada; dado cadastral pertence a CarteiraClientes.
        mockMvc.perform(get(ENDPOINT).header("Authorization", "Bearer " + tokenDeGerente("gerente.vazamento")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classificacaoRiscoCreditoBase").doesNotExist())
                .andExpect(jsonPath("$.situacaoConta").doesNotExist())
                .andExpect(jsonPath("$.nome").doesNotExist())
                .andExpect(jsonPath("$.cpfMascarado").doesNotExist())
                .andExpect(jsonPath("$.clienteId").doesNotExist());
    }

    @Test
    void consultadoEm_eOInstanteDaConsulta_naoADataDeAtualizacaoDoHost() throws Exception {
        // O stub declara datAtuLim de 2020; consultadoEm precisa ser de agora.
        String consultadoEm = mockMvc.perform(get(ENDPOINT)
                        .header("Authorization", "Bearer " + tokenDeGerente("gerente.relogio")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(consultadoEm).doesNotContain("2020");
        assertThat(Instant.parse(consultadoEm.replaceAll(".*\"consultadoEm\":\"([^\"]+)\".*", "$1")))
                .isAfter(Instant.now().minusSeconds(60));
    }

    // --- AC23: sem direito, 403 e nenhuma chamada ao CoreLegado ------------------------------

    @Test
    void semDireitoDeAtendimento_e403_eNenhumaChamadaAoCoreLegado() throws Exception {
        dependenciasExternas.stubFor(WireMock.get(urlEqualTo(PATH_CONTEXTO))
                .willReturn(aResponse().withStatus(403)));

        mockMvc.perform(get(ENDPOINT).header("Authorization", "Bearer " + tokenDeGerente("gerente.sem.direito")))
                .andExpect(status().isForbidden());

        dependenciasExternas.verify(0, postRequestedFor(urlEqualTo(PATH_CORE)));
    }

    @Test
    void contaQueCarteiraClientesNaoReconhece_e404_eNenhumaChamadaAoCoreLegado() throws Exception {
        dependenciasExternas.stubFor(WireMock.get(urlEqualTo(PATH_CONTEXTO))
                .willReturn(aResponse().withStatus(404)));

        mockMvc.perform(get(ENDPOINT).header("Authorization", "Bearer " + tokenDeGerente("gerente.conta.404")))
                .andExpect(status().isNotFound());

        dependenciasExternas.verify(0, postRequestedFor(urlEqualTo(PATH_CORE)));
    }

    @Test
    void carteiraClientesIndisponivel_e503_eNenhumaChamadaAoCoreLegado() throws Exception {
        // Falha de comunicacao nao e resposta de negocio: nao se conclui nada sobre o direito de
        // atendimento, e o Core continua intocado.
        dependenciasExternas.stubFor(WireMock.get(urlEqualTo(PATH_CONTEXTO))
                .willReturn(aResponse().withStatus(503)));

        mockMvc.perform(get(ENDPOINT).header("Authorization", "Bearer " + tokenDeGerente("gerente.carteira.503")))
                .andExpect(status().isServiceUnavailable());

        dependenciasExternas.verify(0, postRequestedFor(urlEqualTo(PATH_CORE)));
    }

    // --- AC21: Token Exchange encadeado, reduzindo capability --------------------------------

    @Test
    void aoContinuarAOperacaoContraCarteiraClientes_apresentaTokenComAAudienceCorreta() throws Exception {
        mockMvc.perform(get(ENDPOINT).header("Authorization", "Bearer " + tokenDeGerente("gerente.aud.correta")))
                .andExpect(status().isOk());

        LoggedRequest chamadaACarteira = umaRequisicao(getRequestedFor(urlEqualTo(PATH_CONTEXTO)));
        String bearer = chamadaACarteira.getHeader("Authorization").replace("Bearer ", "");

        assertThat(audienceDe(bearer))
                .as("o token apresentado a CarteiraClientes precisa ter sido emitido para ela (AC21)")
                .containsExactly("servico-carteira-clientes");
    }

    @Test
    void aTrocaEncadeadaPedeAAudienceDoDestinoEReduzOScope() throws Exception {
        mockMvc.perform(get(ENDPOINT).header("Authorization", "Bearer " + tokenDeGerente("gerente.troca")))
                .andExpect(status().isOk());

        Map<String, String> parametros = formDe(umaRequisicao(postRequestedFor(urlEqualTo(PATH_TOKEN))));

        assertThat(parametros.get("grant_type")).isEqualTo("urn:ietf:params:oauth:grant-type:token-exchange");
        assertThat(parametros.get("audience")).isEqualTo("servico-carteira-clientes");
        assertThat(parametros.get("scope"))
                .as("a segunda perna da cadeia reduz capability: pede so o que precisa la na frente")
                .isEqualTo("carteira.leitura");
        assertThat(parametros.get("scope"))
                .as("credito.leitura nao tem sentido em servico-carteira-clientes e nao pode ser propagado")
                .doesNotContain("credito.leitura");
    }

    @Test
    void oSubjectTokenDaTrocaEOProprioTokenQueAutenticouARequisicao() throws Exception {
        String tokenDoUsuario = tokenDeGerente("gerente.subject");

        mockMvc.perform(get(ENDPOINT).header("Authorization", "Bearer " + tokenDoUsuario))
                .andExpect(status().isOk());

        Map<String, String> parametros = formDe(umaRequisicao(postRequestedFor(urlEqualTo(PATH_TOKEN))));

        // Delegacao de verdade: a operacao continua em nome do mesmo usuario, e nao vira uma
        // chamada de sistema anonima no meio do caminho.
        assertThat(parametros.get("subject_token")).isEqualTo(tokenDoUsuario);
        // RFC 8693: o subject token e um JWT, e e assim que ele se identifica.
        assertThat(parametros.get("subject_token_type")).isEqualTo("urn:ietf:params:oauth:token-type:jwt");
    }

    @Test
    void oTokenDoUsuarioNuncaEReencaminhadoDiretamenteACarteiraClientes() throws Exception {
        String tokenDoUsuario = tokenDeGerente("gerente.nao.reenvia");

        mockMvc.perform(get(ENDPOINT).header("Authorization", "Bearer " + tokenDoUsuario))
                .andExpect(status().isOk());

        String bearerApresentado = umaRequisicao(getRequestedFor(urlEqualTo(PATH_CONTEXTO)))
                .getHeader("Authorization").replace("Bearer ", "");

        // Reutilizar o token destinado a Credito o transformaria em credencial de plataforma --
        // exatamente o que ADR-0015 evita.
        assertThat(bearerApresentado).isNotEqualTo(tokenDoUsuario);
    }

    // --- Helpers -----------------------------------------------------------------------------

    private static LoggedRequest umaRequisicao(RequestPatternBuilder padrao) {
        List<LoggedRequest> requisicoes = dependenciasExternas.findAll(padrao);
        assertThat(requisicoes).hasSize(1);
        return requisicoes.getFirst();
    }

    private static Map<String, String> formDe(LoggedRequest requisicao) {
        return java.util.Arrays.stream(requisicao.getBodyAsString().split("&"))
                .map(par -> par.split("=", 2))
                .collect(Collectors.toMap(
                        par -> URLDecoder.decode(par[0], StandardCharsets.UTF_8),
                        par -> par.length > 1 ? URLDecoder.decode(par[1], StandardCharsets.UTF_8) : ""));
    }

    private static List<String> audienceDe(String jwt) {
        try {
            return SignedJWT.parse(jwt).getJWTClaimsSet().getAudience();
        } catch (ParseException e) {
            throw new IllegalStateException("token apresentado nao e um JWT legivel", e);
        }
    }

    private static String tokenDelegado() {
        try {
            com.nimbusds.jwt.JWTClaimsSet claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                    .subject("gerente.a")
                    .audience("servico-carteira-clientes")
                    .issueTime(Date.from(Instant.now().minusSeconds(5)))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .claim("scope", "carteira.leitura")
                    .claim("papeis", List.of("GERENTE_RELACIONAMENTO"))
                    .build();

            SignedJWT jwt = new SignedJWT(
                    new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256)
                            .type(com.nimbusds.jose.JOSEObjectType.JWT).build(),
                    claims);
            jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(
                    (java.security.interfaces.RSAPrivateKey) JwtTestSupport.KEY_PAIR.getPrivate()));
            return jwt.serialize();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String corpo) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(corpo);
    }
}
