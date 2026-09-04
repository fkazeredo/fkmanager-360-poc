package com.fkmanager360.credito.config;

import com.fkmanager360.credito.adapter.out.persistence.CreditoPersistenceOperations;
import com.fkmanager360.credito.application.port.out.EntregasEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.RegistroIdempotenciaPort;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.SolicitacoesAumentoLimitePort;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
        properties = {
                "credito.security.expected-audience=" + JwtDecoderTestConfig.EXPECTED_AUDIENCE,
                // application.yml nao tem mais default para o client-secret (fail-fast, ADR-0014).
                "AUTH_SERVER_CREDITO_CLIENT_SECRET=segredo-de-teste",
                // S6 nao exercita persistencia (isso e S3, com Testcontainers): sem esta exclusao,
                // a autoconfiguracao do DataSource tentaria abrir uma conexao real na
                // inicializacao do contexto, a partir de #0003 (mesmo padrao ja estabelecido em
                // CarteiraSegurancaTest). Flyway desligado pelo mesmo motivo -- sem datasource
                // real, ele nao teria contra o que migrar.
                "spring.flyway.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration",
                // #0004: sem isto, o dispatcher real dispararia a cada ~1s contra as portas
                // mockadas abaixo (que devolveriam null sem stub), sem nenhum ganho para um teste
                // que nao exercita entrega.
                "credito.efetivacao.entrega.habilitada=false"
        })
@AutoConfigureMockMvc
@Import(JwtDecoderTestConfig.class)
class CreditoSegurancaTest {

    private static final String AUD = JwtDecoderTestConfig.EXPECTED_AUDIENCE;
    private static final String PATH_DIREITO = "/clientes/1/contas/10001/direito-de-atendimento";
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

    // S6 nao reexamina persistencia (isso e S3, com Testcontainers): estes testes de seguranca
    // exercitam somente o GET de leitura, que nunca toca a persistencia. Mockar as portas aqui --
    // e nao so excluir a autoconfiguracao do DataSource -- evita que o Spring precise instanciar
    // os adapters reais (que exigem DataSource/EntityManager de verdade) so para o contexto subir.
    // Mesmo padrao ja estabelecido em CarteiraSegurancaTest.
    @MockitoBean
    private SolicitacoesAumentoLimitePort solicitacoesAumentoLimitePort;

    @MockitoBean
    private RegistroIdempotenciaPort registroIdempotenciaPort;

    // CreditoPersistenceOperations e o fragment transacional de TX1/TX2, e nao implementa nenhuma
    // port -- entao mockar as duas portas acima nao o substitui, e o component scan o instanciaria
    // avidamente exigindo o EntityManager que este contexto deliberadamente nao tem. Declarar aqui
    // que este teste tambem nao exercita esse bean e a forma correta: a exclusao pertence ao teste
    // que escolheu subir sem banco, nunca a producao (a alternativa seria um @Lazy no bean real,
    // isto e, uma anotacao de producao cuja unica justificativa seria este arquivo).
    @MockitoBean
    private CreditoPersistenceOperations creditoPersistenceOperations;

    // #0004: mesma razao das duas portas acima -- sem mocka-las, o component scan instanciaria
    // JdbcEntregasEfetivacaoAdapter/JpaResultadoEfetivacaoAdapter (via o bean
    // EntregarInstrucoesEfetivacao, que os exige no construtor), exigindo JdbcClient/EntityManager
    // que este contexto nao tem.
    @MockitoBean
    private EntregasEfetivacaoPort entregasEfetivacaoPort;

    @MockitoBean
    private ResultadoEfetivacaoPort resultadoEfetivacaoPort;

    @BeforeEach
    void comportamentoPadraoDasDependencias() {
        dependenciasExternas.resetAll();

        // servidor-autorizacao: devolve um token delegado com aud = servico-carteira-clientes.
        dependenciasExternas.stubFor(post(urlEqualTo(PATH_TOKEN)).willReturn(json("""
                {"access_token":"%s","issued_token_type":"urn:ietf:params:oauth:token-type:access_token",
                 "token_type":"Bearer","expires_in":300}
                """.formatted(tokenDelegado()))));

        // CarteiraClientes: concede o atendimento por padrao -- 204 sem corpo, a operacao
        // estreita que este servico consome (I4 do review de #0002).
        dependenciasExternas.stubFor(WireMock.get(urlEqualTo(PATH_DIREITO))
                .willReturn(aResponse().withStatus(204)));

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
        dependenciasExternas.stubFor(WireMock.get(urlEqualTo(PATH_DIREITO))
                .willReturn(aResponse().withStatus(403)));

        mockMvc.perform(get(ENDPOINT).header("Authorization", "Bearer " + tokenDeGerente("gerente.sem.direito")))
                .andExpect(status().isForbidden());

        dependenciasExternas.verify(0, postRequestedFor(urlEqualTo(PATH_CORE)));
    }

    @Test
    void contaQueCarteiraClientesNaoReconhece_e404_eNenhumaChamadaAoCoreLegado() throws Exception {
        dependenciasExternas.stubFor(WireMock.get(urlEqualTo(PATH_DIREITO))
                .willReturn(aResponse().withStatus(404)));

        mockMvc.perform(get(ENDPOINT).header("Authorization", "Bearer " + tokenDeGerente("gerente.conta.404")))
                .andExpect(status().isNotFound());

        dependenciasExternas.verify(0, postRequestedFor(urlEqualTo(PATH_CORE)));
    }

    @Test
    void carteiraClientesIndisponivel_e503_eNenhumaChamadaAoCoreLegado() throws Exception {
        // Falha de comunicacao nao e resposta de negocio: nao se conclui nada sobre o direito de
        // atendimento, e o Core continua intocado.
        dependenciasExternas.stubFor(WireMock.get(urlEqualTo(PATH_DIREITO))
                .willReturn(aResponse().withStatus(503)));

        mockMvc.perform(get(ENDPOINT).header("Authorization", "Bearer " + tokenDeGerente("gerente.carteira.503")))
                .andExpect(status().isServiceUnavailable());

        dependenciasExternas.verify(0, postRequestedFor(urlEqualTo(PATH_CORE)));
    }

    /**
     * O que o I4 do review resolveu estruturalmente: a operacao estreita nunca consulta dados
     * mestres do Cliente dentro de CarteiraClientes, entao uma indisponibilidade dessa consulta
     * cadastral -- que aqui nem existe mais como possibilidade, porque o endpoint consumido nao a
     * invoca -- nao pode mais bloquear a leitura do limite. A prova do lado de CarteiraClientes
     * esta em {@code AtendimentoSegurancaTest.direitoDeAtendimento_nuncaConsultaDadosMestres};
     * aqui a prova e que o caminho feliz de Credito depende apenas de {@code PATH_DIREITO}
     * responder, nunca de dados cadastrais.
     */
    @Test
    void comDireitoConcedido_naoDependeDeNenhumDadoCadastralDoCliente() throws Exception {
        // O stub de PATH_DIREITO no @BeforeEach ja e 204 sem corpo -- nenhum nome, nenhum CPF.
        // Se este teste passa, a leitura do limite nao depende de dado cadastral algum.
        mockMvc.perform(get(ENDPOINT).header("Authorization", "Bearer " + tokenDeGerente("gerente.sem.cadastro")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limiteChequeEspecialVigente").value(500000));
    }

    // --- Recusa real do Token Exchange pelo servidor-autorizacao -----------------------------

    /**
     * O que acontece quando a SEGUNDA troca (Credito -> CarteiraClientes) e recusada pelo
     * servidor-autorizacao de verdade -- nao um 403/503 de CarteiraClientes, e sim o proprio
     * endpoint de token recusando a emissao. Sem o tratamento em
     * {@code CarteiraClientesAdapter.confirmarDireitoDeAtendimento}, esta excecao nao e
     * {@code RestClientException} e escapa sem handler ate um 500 generico.
     */
    @Test
    void tokenExchangeRecusadoPeloServidorDeAutorizacao_vira503_naoErroGenerico() throws Exception {
        dependenciasExternas.stubFor(post(urlEqualTo(PATH_TOKEN)).willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"error":"invalid_scope","error_description":"scope amplificado"}
                        """)));

        mockMvc.perform(get(ENDPOINT).header("Authorization", "Bearer " + tokenDeGerente("gerente.token.recusado")))
                .andExpect(status().isServiceUnavailable());

        dependenciasExternas.verify(0, postRequestedFor(urlEqualTo(PATH_CORE)));
    }

    // --- Taxonomia 502/503/504 do CoreLegado na borda -----------------------------------------

    @Test
    void coreLegadoIndisponivel_e503() throws Exception {
        dependenciasExternas.stubFor(post(urlEqualTo(PATH_CORE)).willReturn(aResponse().withStatus(503)));

        mockMvc.perform(get(ENDPOINT).header("Authorization", "Bearer " + tokenDeGerente("gerente.core.503")))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void coreLegadoRespostaIlegivel_e502() throws Exception {
        dependenciasExternas.stubFor(post(urlEqualTo(PATH_CORE)).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"codRet\":\"000\",")));

        mockMvc.perform(get(ENDPOINT).header("Authorization", "Bearer " + tokenDeGerente("gerente.core.502")))
                .andExpect(status().isBadGateway());
    }

    @Test
    void coreLegadoTimeout_e504() throws Exception {
        dependenciasExternas.stubFor(post(urlEqualTo(PATH_CORE)).willReturn(aResponse()
                .withStatus(200).withFixedDelay(6000)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"codRet\":\"121\"}")));

        mockMvc.perform(get(ENDPOINT).header("Authorization", "Bearer " + tokenDeGerente("gerente.core.504")))
                .andExpect(status().isGatewayTimeout());
    }

    // --- AC21: Token Exchange encadeado, reduzindo capability --------------------------------

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

        String bearerApresentado = umaRequisicao(getRequestedFor(urlEqualTo(PATH_DIREITO)))
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
