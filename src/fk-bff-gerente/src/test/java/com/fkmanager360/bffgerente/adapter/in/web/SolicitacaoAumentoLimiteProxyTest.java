package com.fkmanager360.bffgerente.adapter.in.web;

import com.fkmanager360.bffgerente.config.DelegatedTokenResolver;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S6 do proxy de submissao (plano #0003, secao 9 "bff-gerente"): corpo e {@code Idempotency-Key}
 * repassados intactos; status de sucesso propagado (201/200); envelope de erro publico proprio
 * com allow-list de {@code (status, codigo)}; {@code codigo} desconhecido ou corpo upstream
 * ilegivel virando {@code 502 DEPENDENCIA_INDISPONIVEL}; e a confirmacao de que {@code detail}/
 * {@code instance} do upstream nunca atravessam para o browser.
 *
 * <p>Mesmo padrao de {@link ComposicaoAtendimentoTest}: um {@code WireMockServer} representando
 * {@code servico-credito}, {@link DelegatedTokenResolver} mockado (a obtencao do token em si ja e
 * provada em {@code BffSegurancaTest}/S6 do servidor-autorizacao), sessao Redis excluida porque o
 * objeto deste teste e o proxy e o mapeamento de erro, nao a sessao.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration,"
                        + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
                "spring.security.oauth2.client.provider.servidor-autorizacao.authorization-uri=http://servidor-autorizacao.invalid/oauth2/authorize",
                "spring.security.oauth2.client.provider.servidor-autorizacao.token-uri=http://servidor-autorizacao.invalid/oauth2/token",
                "spring.security.oauth2.client.provider.servidor-autorizacao.jwk-set-uri=http://servidor-autorizacao.invalid/oauth2/jwks",
                "spring.security.oauth2.client.provider.servidor-autorizacao.user-info-uri=http://servidor-autorizacao.invalid/userinfo",
                "spring.security.oauth2.client.provider.servidor-autorizacao.user-name-attribute=sub"
        })
@AutoConfigureMockMvc
class SolicitacaoAumentoLimiteProxyTest {

    private static final String PATH_SOLICITACAO = "/clientes/1/contas/10001/solicitacoes-aumento-limite";
    private static final String ENDPOINT = "/api/clientes/1/contas/10001/solicitacoes-aumento-limite";
    private static final String TOKEN_ESCRITA = "token-para-servico-credito-escrita";

    private static WireMockServer credito;

    @BeforeAll
    static void subirCredito() {
        credito = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        credito.start();
    }

    @AfterAll
    static void pararCredito() {
        credito.stop();
    }

    @DynamicPropertySource
    static void apontarCredito(DynamicPropertyRegistry registry) {
        registry.add("bff-gerente.credito.base-url", () -> "http://localhost:" + credito.port());
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DelegatedTokenResolver tokenResolver;

    @BeforeEach
    void comportamentoPadrao() {
        credito.resetAll();
        when(tokenResolver.tokenPara(eq("credito-escrita-exchange"), any(), any(), any()))
                .thenReturn(TOKEN_ESCRITA);
    }

    private static RequestPostProcessor gerenteAutenticado() {
        return SecurityMockMvcRequestPostProcessors.oidcLogin().idToken(token -> token.subject("gerente.a"));
    }

    // --- Encaminhamento intacto ----------------------------------------------------------------

    @Test
    void submeter_repassaCorpoEHeaderIntactosEDevolveOStatusDeCredito() throws Exception {
        String idemKey = UUID.randomUUID().toString();
        String corpo = """
                {"limiteSolicitado":600000,"limiteVigenteVisto":500000,
                 "manifestacaoCliente":{"canalManifestacao":"PRESENCIAL","observacao":"balcao"}}""";
        String corpoDeCredito = """
                {"solicitacaoId":"11111111-1111-1111-1111-111111111111","contaId":"10001",
                 "status":"AGUARDANDO_EFETIVACAO","limiteChequeEspecialVigente":500000,
                 "limiteSolicitado":600000,"limiteSolicitadoPendenteDeEfetivacao":600000,
                 "decisao":{"resultado":"APROVADA","motivo":"DENTRO_DA_POLITICA_AUTOMATICA",
                 "versaoPoliticaCredito":"v1","decididaEm":"2026-09-02T16:00:00Z"},
                 "registradaEm":"2026-09-02T16:00:00Z"}""";
        credito.stubFor(WireMock.post(urlEqualTo(PATH_SOLICITACAO)).willReturn(aResponse()
                .withStatus(201).withHeader("Content-Type", "application/json").withBody(corpoDeCredito)));

        mockMvc.perform(post(ENDPOINT).with(gerenteAutenticado()).with(SecurityMockMvcRequestPostProcessors.csrf())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AGUARDANDO_EFETIVACAO"))
                .andExpect(jsonPath("$.limiteSolicitadoPendenteDeEfetivacao").value(600000));

        // Corpo e header propagados INTACTOS -- nada gerado, regenerado ou reinterpretado.
        credito.verify(postRequestedFor(urlEqualTo(PATH_SOLICITACAO))
                .withHeader("Idempotency-Key", WireMock.equalTo(idemKey))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + TOKEN_ESCRITA))
                .withRequestBody(WireMock.equalToJson(corpo)));
    }

    @Test
    void submeter_replay_devolve200() throws Exception {
        credito.stubFor(WireMock.post(urlEqualTo(PATH_SOLICITACAO)).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"solicitacaoId":"11111111-1111-1111-1111-111111111111","status":"REJEITADA"}""")));

        mockMvc.perform(post(ENDPOINT).with(gerenteAutenticado()).with(SecurityMockMvcRequestPostProcessors.csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJEITADA"));
    }

    // --- Envelope publico: allow-list de (status, codigo) ---------------------------------------

    static Stream<Arguments> statusECodigosConhecidos() {
        return Stream.of(
                Arguments.of(400, "IDEMPOTENCY_KEY_INVALIDA"),
                Arguments.of(403, "SEM_DIREITO_DE_ATENDIMENTO"),
                Arguments.of(404, "CONTA_NAO_ENCONTRADA"),
                Arguments.of(409, "LIMITE_VIGENTE_DESATUALIZADO"),
                Arguments.of(422, "COMANDO_INVALIDO"));
    }

    @ParameterizedTest(name = "{0} {1} preserva status e codigo, mas nunca detail/instance")
    @MethodSource("statusECodigosConhecidos")
    void submeter_erroDeNegocioConhecido_preservaStatusECodigo_semVazarDetalheUpstream(int statusUpstream, String codigo)
            throws Exception {
        credito.stubFor(WireMock.post(urlEqualTo(PATH_SOLICITACAO)).willReturn(aResponse()
                .withStatus(statusUpstream).withHeader("Content-Type", "application/json").withBody("""
                        {"status":%d,"codigo":"%s","detail":"mensagem interna de diagnostico de Credito",
                         "instance":"/clientes/1/contas/10001/solicitacoes-aumento-limite"}"""
                        .formatted(statusUpstream, codigo))));

        mockMvc.perform(post(ENDPOINT).with(gerenteAutenticado()).with(SecurityMockMvcRequestPostProcessors.csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(statusUpstream))
                .andExpect(jsonPath("$.codigo").value(codigo))
                // Guardrail explicito: detail/instance do upstream NUNCA atravessam para o browser.
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());
    }

    @Test
    void submeter_codigoDesconhecido_vira502DependenciaIndisponivel_nuncaUmErroRepassadoCegamente() throws Exception {
        credito.stubFor(WireMock.post(urlEqualTo(PATH_SOLICITACAO)).willReturn(aResponse()
                .withStatus(409).withHeader("Content-Type", "application/json").withBody("""
                        {"status":409,"codigo":"CODIGO_QUE_O_BFF_AINDA_NAO_CONHECE","detail":"x"}""")));

        mockMvc.perform(post(ENDPOINT).with(gerenteAutenticado()).with(SecurityMockMvcRequestPostProcessors.csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.codigo").value("DEPENDENCIA_INDISPONIVEL"));
    }

    @Test
    void submeter_corpoUpstreamIlegivel_vira502DependenciaIndisponivel() throws Exception {
        credito.stubFor(WireMock.post(urlEqualTo(PATH_SOLICITACAO)).willReturn(aResponse()
                .withStatus(422).withHeader("Content-Type", "application/json").withBody("{ isto nao e json valido")));

        mockMvc.perform(post(ENDPOINT).with(gerenteAutenticado()).with(SecurityMockMvcRequestPostProcessors.csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.codigo").value("DEPENDENCIA_INDISPONIVEL"));
    }

    // --- Validacao na propria borda do BFF, sem chamar Credito -----------------------------------

    @Test
    void submeter_semIdempotencyKey_e400ComCodigo_semChamarCredito() throws Exception {
        mockMvc.perform(post(ENDPOINT).with(gerenteAutenticado()).with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("IDEMPOTENCY_KEY_AUSENTE"));

        credito.verify(0, postRequestedFor(urlEqualTo(PATH_SOLICITACAO)));
    }

    @Test
    void submeter_contaIdForaDoFormatoHost_e400ComCodigo_semChamarCredito() throws Exception {
        mockMvc.perform(post("/api/clientes/1/contas/abc/solicitacoes-aumento-limite")
                        .with(gerenteAutenticado()).with(SecurityMockMvcRequestPostProcessors.csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("IDENTIFICADOR_INVALIDO"));

        credito.verify(0, postRequestedFor(urlEqualTo(PATH_SOLICITACAO)));
    }

    @Test
    void submeter_semCsrf_e403() throws Exception {
        mockMvc.perform(post(ENDPOINT).with(gerenteAutenticado())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        credito.verify(0, postRequestedFor(urlEqualTo(PATH_SOLICITACAO)));
    }
}
