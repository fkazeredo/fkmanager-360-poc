package com.fkmanager360.credito.config;

import com.fkmanager360.credito.adapter.out.persistence.CreditoPersistenceOperations;
import com.fkmanager360.credito.application.port.out.EntregasEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.RegistroIdempotenciaPort;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoRecebido;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroEfetivacao;
import com.fkmanager360.credito.application.port.out.SolicitacaoNaoEncontradaException;
import com.fkmanager360.credito.application.port.out.SolicitacoesAumentoLimitePort;
import com.fkmanager360.credito.domain.ProtocoloCore;
import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S6 (ADR-0018): routing HTTP, autenticacao {@code client_credentials}, autorizacao por scope
 * (sem {@code hasRole} -- token de maquina nao carrega {@code papeis}) e status codes do callback
 * de confirmacao (#0005). Nao reexamina a classificacao terminal em tres eixos (isso e S3) nem a
 * composicao de {@code RegistrarResultadoEfetivacao} (isso e S2) -- aqui {@link ResultadoEfetivacaoPort}
 * e mockado, e o teste prova so que a resposta HTTP certa sai de cada sealed devolvida.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "credito.security.expected-audience=" + JwtDecoderTestConfig.EXPECTED_AUDIENCE,
                "AUTH_SERVER_CREDITO_CLIENT_SECRET=segredo-de-teste",
                "spring.flyway.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration",
                "credito.efetivacao.entrega.habilitada=false"
        })
@AutoConfigureMockMvc
@Import(JwtDecoderTestConfig.class)
class CallbackSegurancaTest {

    private static final String AUD = JwtDecoderTestConfig.EXPECTED_AUDIENCE;
    private static final String PATH_TOKEN = "/oauth2/token";
    private static final String ENDPOINT = "/callbacks/efetivacoes";

    private static WireMockServer dependenciasExternas;

    @BeforeAll
    static void subirDependenciasExternas() {
        dependenciasExternas = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        dependenciasExternas.start();
        dependenciasExternas.stubFor(post(urlEqualTo(PATH_TOKEN)).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"irrelevante\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
    }

    @AfterAll
    static void pararDependenciasExternas() {
        dependenciasExternas.stop();
    }

    @DynamicPropertySource
    static void apontarDependenciasParaOMockServer(DynamicPropertyRegistry registry) {
        registry.add("credito.core-legado.base-url", () -> "http://localhost:" + dependenciasExternas.port());
        registry.add("credito.carteira-clientes.base-url", () -> "http://localhost:" + dependenciasExternas.port());
        registry.add("spring.security.oauth2.client.provider.servidor-autorizacao.token-uri",
                () -> "http://localhost:" + dependenciasExternas.port() + PATH_TOKEN);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    // Mesmo padrao de CreditoSegurancaTest: mockar as portas de persistencia evita que o
    // component scan precise instanciar os adapters reais (que exigem DataSource/EntityManager),
    // so para o contexto subir sem banco.
    @MockitoBean
    private SolicitacoesAumentoLimitePort solicitacoesAumentoLimitePort;

    @MockitoBean
    private RegistroIdempotenciaPort registroIdempotenciaPort;

    @MockitoBean
    private CreditoPersistenceOperations creditoPersistenceOperations;

    @MockitoBean
    private EntregasEfetivacaoPort entregasEfetivacaoPort;

    @MockitoBean
    private ResultadoEfetivacaoPort resultadoEfetivacaoPort;

    private static String corpo(String idEft, String numPrt, String codRet, String vlrLimEft) {
        return """
                {"idEft":"%s","numPrt":"%s","codRet":"%s"%s}
                """.formatted(idEft, numPrt, codRet, vlrLimEft == null ? "" : ",\"vlrLimEft\":\"" + vlrLimEft + "\"");
    }

    private static String tokenMaquina() {
        return JwtTestSupport.machineToken("simulador-core-legado", AUD, "credito.callback");
    }

    // --- Autenticacao e autorizacao --------------------------------------------------------

    @Test
    void semToken_e401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post(ENDPOINT).contentType("application/json")
                        .content(corpo(UUID.randomUUID().toString(), "PRT-1", "000", "600000")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenComAudienceErrada_e401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post(ENDPOINT).contentType("application/json")
                        .header("Authorization", "Bearer " + JwtTestSupport.tokenWithWrongAudience("simulador"))
                        .content(corpo(UUID.randomUUID().toString(), "PRT-1", "000", "600000")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenDeGerenteSemScopeCallback_e403() throws Exception {
        // Token humano valido (credito.leitura/carteira.leitura, papel GERENTE_RELACIONAMENTO) --
        // sem credito.callback, mesmo carregando papel, e recusado: scope e papel sao perguntas
        // distintas.
        mockMvc.perform(MockMvcRequestBuilders.post(ENDPOINT).contentType("application/json")
                        .header("Authorization", "Bearer " + JwtTestSupport.validToken("gerente.a", AUD, java.util.List.of("GERENTE_RELACIONAMENTO")))
                        .content(corpo(UUID.randomUUID().toString(), "PRT-1", "000", "600000")))
                .andExpect(status().isForbidden());

        verify(resultadoEfetivacaoPort, never()).registrar(any(), any(), any(), any(), any());
    }

    @Test
    void tokenMaquinaSemPapeis_temAcessoAoEndpointMaquinaAMaquina() throws Exception {
        // Prova que o endpoint NUNCA exige hasRole -- um token client_credentials nao carrega
        // "papeis", e mesmo assim precisa ser aceito quando o scope esta correto.
        UUID efetivacaoId = UUID.randomUUID();
        when(resultadoEfetivacaoPort.registrar(any(), any(), any(), any(), any()))
                .thenReturn(new ResultadoRegistroEfetivacao.Concluida(StatusSolicitacaoAumentoLimite.EFETIVADA, Duration.ofMinutes(3)));

        mockMvc.perform(MockMvcRequestBuilders.post(ENDPOINT).contentType("application/json")
                        .header("Authorization", "Bearer " + tokenMaquina())
                        .content(corpo(efetivacaoId.toString(), "PRT-1", "000", "600000")))
                .andExpect(status().isOk());
    }

    // --- Sealed ResultadoRegistroEfetivacao -> status/corpo HTTP ----------------------------

    @Test
    void sucessoConcluido_e200ComCorpoProcessado() throws Exception {
        when(resultadoEfetivacaoPort.registrar(any(), any(), any(), any(), any()))
                .thenReturn(new ResultadoRegistroEfetivacao.Concluida(StatusSolicitacaoAumentoLimite.EFETIVADA, Duration.ofMinutes(3)));

        mockMvc.perform(MockMvcRequestBuilders.post(ENDPOINT).contentType("application/json")
                        .header("Authorization", "Bearer " + tokenMaquina())
                        .content(corpo(UUID.randomUUID().toString(), "PRT-1", "000", "600000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultado").value("PROCESSADO"));
    }

    @Test
    void duplicadoIdentico_e200ComCorpoJaConcluida() throws Exception {
        when(resultadoEfetivacaoPort.registrar(any(), any(), any(), any(), any()))
                .thenReturn(new ResultadoRegistroEfetivacao.JaTerminalIdentica(StatusSolicitacaoAumentoLimite.EFETIVADA));

        mockMvc.perform(MockMvcRequestBuilders.post(ENDPOINT).contentType("application/json")
                        .header("Authorization", "Bearer " + tokenMaquina())
                        .content(corpo(UUID.randomUUID().toString(), "PRT-1", "000", "600000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultado").value("JA_CONCLUIDA"));
    }

    @Test
    void contraditorioSobreTerminal_e200ComCorpoConflitoRegistrado() throws Exception {
        when(resultadoEfetivacaoPort.registrar(any(), any(), any(), any(), any()))
                .thenReturn(new ResultadoRegistroEfetivacao.JaTerminalContraditoria(StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO));

        mockMvc.perform(MockMvcRequestBuilders.post(ENDPOINT).contentType("application/json")
                        .header("Authorization", "Bearer " + tokenMaquina())
                        .content(corpo(UUID.randomUUID().toString(), "PRT-1", "000", "600000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultado").value("CONFLITO_REGISTRADO"));
    }

    @Test
    void sucessoIncoerente_e200ComCorpoAnomaliaRegistrada() throws Exception {
        when(resultadoEfetivacaoPort.registrar(any(), any(), any(), any(), any()))
                .thenReturn(new ResultadoRegistroEfetivacao.SucessoIncoerente());

        mockMvc.perform(MockMvcRequestBuilders.post(ENDPOINT).contentType("application/json")
                        .header("Authorization", "Bearer " + tokenMaquina())
                        .content(corpo(UUID.randomUUID().toString(), "PRT-1", "000", "999999")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultado").value("ANOMALIA_REGISTRADA"));
    }

    @Test
    void protocoloDivergente_e200ComCorpoAnomaliaRegistrada() throws Exception {
        when(resultadoEfetivacaoPort.registrar(any(), any(), any(), any(), any()))
                .thenReturn(new ResultadoRegistroEfetivacao.ProtocoloDivergente());

        mockMvc.perform(MockMvcRequestBuilders.post(ENDPOINT).contentType("application/json")
                        .header("Authorization", "Bearer " + tokenMaquina())
                        .content(corpo(UUID.randomUUID().toString(), "PRT-2", "000", "600000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultado").value("ANOMALIA_REGISTRADA"));
    }

    @Test
    void efetivacaoIdDesconhecido_e404() throws Exception {
        when(resultadoEfetivacaoPort.registrar(any(), any(), any(), any(), any()))
                .thenThrow(new SolicitacaoNaoEncontradaException("nenhuma solicitacao para este EfetivacaoId"));

        mockMvc.perform(MockMvcRequestBuilders.post(ENDPOINT).contentType("application/json")
                        .header("Authorization", "Bearer " + tokenMaquina())
                        .content(corpo(UUID.randomUUID().toString(), "PRT-1", "000", "600000")))
                .andExpect(status().isNotFound());
    }

    // --- Payload malformado (400), sem chamar o caso de uso ---------------------------------

    @Test
    void idEftNaoEUuid_e400() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post(ENDPOINT).contentType("application/json")
                        .header("Authorization", "Bearer " + tokenMaquina())
                        .content(corpo("nao-e-um-uuid", "PRT-1", "000", "600000")))
                .andExpect(status().isBadRequest());

        verify(resultadoEfetivacaoPort, never()).registrar(any(), any(), any(), any(), any());
    }

    @Test
    void vlrLimEftAusenteComCodRetSucesso_e400() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post(ENDPOINT).contentType("application/json")
                        .header("Authorization", "Bearer " + tokenMaquina())
                        .content(corpo(UUID.randomUUID().toString(), "PRT-1", "000", null)))
                .andExpect(status().isBadRequest());

        verify(resultadoEfetivacaoPort, never()).registrar(any(), any(), any(), any(), any());
    }

    @Test
    void numPrtAusente_e400() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post(ENDPOINT).contentType("application/json")
                        .header("Authorization", "Bearer " + tokenMaquina())
                        .content("{\"idEft\":\"" + UUID.randomUUID() + "\",\"codRet\":\"000\",\"vlrLimEft\":\"600000\"}"))
                .andExpect(status().isBadRequest());

        verify(resultadoEfetivacaoPort, never()).registrar(any(), any(), any(), any(), any());
    }

    @Test
    void codRetDesconhecido_e200ComAnomaliaESemChamarOCasoDeUso() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post(ENDPOINT).contentType("application/json")
                        .header("Authorization", "Bearer " + tokenMaquina())
                        .content(corpo(UUID.randomUUID().toString(), "PRT-1", "777", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultado").value("ANOMALIA_REGISTRADA"));

        verify(resultadoEfetivacaoPort, never()).registrar(any(), any(), any(), any(), any());
    }

    // --- numPrt e propagado como ProtocoloCore para a porta de resultado -------------------

    @Test
    void numPrtInformado_ePropagadoParaAPortaDeResultadoComoProtocoloCore() throws Exception {
        when(resultadoEfetivacaoPort.registrar(any(), any(), any(), any(), any()))
                .thenReturn(new ResultadoRegistroEfetivacao.Concluida(StatusSolicitacaoAumentoLimite.EFETIVADA, Duration.ofMinutes(1)));

        mockMvc.perform(MockMvcRequestBuilders.post(ENDPOINT).contentType("application/json")
                        .header("Authorization", "Bearer " + tokenMaquina())
                        .content(corpo(UUID.randomUUID().toString(), "PRT-XYZ", "000", "600000")))
                .andExpect(status().isOk());

        verify(resultadoEfetivacaoPort).registrar(
                any(), any(ResultadoEfetivacaoRecebido.class), eq(Optional.of(new ProtocoloCore("PRT-XYZ"))), any(), any());
    }

    // --- AC36: meters novos respeitam a politica de cardinalidade ---------------------------

    @Test
    void meters_naoCarregamLabelDeIdentificadorDeNegocio() throws Exception {
        when(resultadoEfetivacaoPort.registrar(any(), any(), any(), any(), any()))
                .thenReturn(new ResultadoRegistroEfetivacao.Concluida(StatusSolicitacaoAumentoLimite.EFETIVADA, Duration.ofMinutes(1)));

        mockMvc.perform(MockMvcRequestBuilders.post(ENDPOINT).contentType("application/json")
                        .header("Authorization", "Bearer " + tokenMaquina())
                        .content(corpo(UUID.randomUUID().toString(), "PRT-1", "000", "600000")))
                .andExpect(status().isOk());

        assertThat(meterRegistry.find("efetivacao_callback_resultados_total").counters()).isNotEmpty();
        assertThat(meterRegistry.find("efetivacao_tempo_aguardando_efetivacao").timers()).isNotEmpty();
        meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("efetivacao_"))
                .forEach(meter -> meter.getId().getTags().forEach(tag -> assertThat(tag.getKey())
                        .as("meter %s nao pode carregar identificador de negocio", meter.getId().getName())
                        .isNotIn("clienteId", "contaId", "solicitacaoId", "protocoloCore", "correlationId")));
    }
}
