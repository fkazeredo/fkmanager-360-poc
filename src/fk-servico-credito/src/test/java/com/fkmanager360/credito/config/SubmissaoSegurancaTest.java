package com.fkmanager360.credito.config;

import com.fkmanager360.credito.application.FingerprintCanonico;
import com.fkmanager360.credito.application.port.out.CargaParaDecisao;
import com.fkmanager360.credito.application.port.out.IdempotenciaEmProcessamentoException;
import com.fkmanager360.credito.application.port.out.NovaSolicitacaoAumentoLimite;
import com.fkmanager360.credito.application.port.out.RegistroIdempotencia;
import com.fkmanager360.credito.application.port.out.RegistroIdempotenciaPort;
import com.fkmanager360.credito.application.port.out.ResultadoAplicacaoDecisao;
import com.fkmanager360.credito.application.port.out.SolicitacaoCriada;
import com.fkmanager360.credito.application.port.out.SolicitacaoNaoTerminalExistente;
import com.fkmanager360.credito.application.port.out.SolicitacoesAumentoLimitePort;
import com.fkmanager360.credito.domain.AtorId;
import com.fkmanager360.credito.domain.AtorSistema;
import com.fkmanager360.credito.domain.ClassificacaoRiscoCreditoBase;
import com.fkmanager360.credito.domain.ClienteId;
import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.ContextoDecisaoCredito;
import com.fkmanager360.credito.domain.CorrelationId;
import com.fkmanager360.credito.domain.DadosCreditoCore;
import com.fkmanager360.credito.domain.DecisaoCredito;
import com.fkmanager360.credito.domain.IdempotencyKey;
import com.fkmanager360.credito.domain.LimiteChequeEspecialVigente;
import com.fkmanager360.credito.domain.LimiteSolicitado;
import com.fkmanager360.credito.domain.MotivoDecisaoCredito;
import com.fkmanager360.credito.domain.OrigemSolicitacao;
import com.fkmanager360.credito.domain.ResultadoDecisaoCredito;
import com.fkmanager360.credito.domain.SituacaoConta;
import com.fkmanager360.credito.domain.SolicitacaoId;
import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;
import com.fkmanager360.credito.domain.VersaoPoliticaCredito;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S6 da submissao (plano #0003, secao 9 e 11): todos os status codes e os {@code codigo}s do
 * envelope de erro, a taxonomia 400 vs 422 do parsing, scopes cruzados, AC27 e o guardrail de
 * autoria, presenca/ausencia de {@code limiteSolicitadoPendenteDeEfetivacao} e a metrica
 * {@code decisoes_credito_total}.
 *
 * <p>Mesma infra WireMock+JWT de {@link CreditoSegurancaTest} (token endpoint, CarteiraClientes e
 * CoreLegado por path, {@link JwtTestSupport} para fabricar tokens) -- reaproveitada, nao
 * duplicada. As duas portas de persistencia ({@link SolicitacoesAumentoLimitePort},
 * {@link RegistroIdempotenciaPort}) sao mockadas, exatamente como a etapa de persistencia ja fez
 * em {@code CreditoSegurancaTest}, para que o contexto Spring suba sem banco real -- este teste
 * nao reexamina persistencia (isso e S3, com Testcontainers).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "credito.security.expected-audience=" + JwtDecoderTestConfig.EXPECTED_AUDIENCE,
                "spring.flyway.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration"
        })
@AutoConfigureMockMvc
@Import(JwtDecoderTestConfig.class)
class SubmissaoSegurancaTest {

    private static final String AUD = JwtDecoderTestConfig.EXPECTED_AUDIENCE;
    private static final String PATH_DIREITO = "/clientes/1/contas/10001/direito-de-atendimento";
    private static final String PATH_CORE = "/legado/contas/consulta-credito";
    private static final String PATH_TOKEN = "/oauth2/token";
    private static final String ENDPOINT_GET = "/clientes/1/contas/10001/limite-cheque-especial-vigente";
    private static final String ENDPOINT_POST = "/clientes/1/contas/10001/solicitacoes-aumento-limite";

    private static final ContaId CONTA_ID = new ContaId("10001");
    private static final SolicitacaoId SOLICITACAO_ID_PADRAO = new SolicitacaoId(UUID.randomUUID());

    private static final String CORPO_APROVADO = """
            {"limiteSolicitado":600000,"limiteVigenteVisto":500000,
             "manifestacaoCliente":{"canalManifestacao":"PRESENCIAL","observacao":"cliente pediu no balcao"}}
            """;

    private static final String CORPO_FORA_DA_POLITICA = """
            {"limiteSolicitado":1500000,"limiteVigenteVisto":500000,
             "manifestacaoCliente":{"canalManifestacao":"PRESENCIAL","observacao":"cliente pediu no balcao"}}
            """;

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

    @MockitoBean
    private SolicitacoesAumentoLimitePort solicitacoesAumentoLimitePort;

    @MockitoBean
    private RegistroIdempotenciaPort registroIdempotenciaPort;

    /** Preenchido pelo stub padrao de {@code registrar(...)}, reaproveitado por quem precisar do contexto congelado. */
    private final AtomicReference<ContextoDecisaoCredito> contextoCapturado = new AtomicReference<>();

    @BeforeEach
    void comportamentoPadraoDasDependencias() {
        dependenciasExternas.resetAll();

        dependenciasExternas.stubFor(WireMock.post(urlEqualTo(PATH_TOKEN)).willReturn(json("""
                {"access_token":"%s","issued_token_type":"urn:ietf:params:oauth:token-type:access_token",
                 "token_type":"Bearer","expires_in":300}
                """.formatted(tokenDelegado()))));

        dependenciasExternas.stubFor(WireMock.get(urlEqualTo(PATH_DIREITO))
                .willReturn(aResponse().withStatus(204)));

        // vlrLimChqEsp = 500000 centavos (R$ 5.000,00), sitCta REGULAR, codRscCrd BAIXO.
        dependenciasExternas.stubFor(WireMock.post(urlEqualTo(PATH_CORE)).willReturn(json("""
                {"codRet":"000","msgRet":"OK","numCta":"0000010001","vlrLimChqEsp":"000000000500000",
                 "sitCta":"01","codRscCrd":"1","datAtuLim":"20200101"}
                """)));

        // Pre-check de idempotencia: nenhum registro existente, por padrao (primeira submissao).
        when(registroIdempotenciaPort.buscar(any(), any())).thenReturn(Optional.empty());

        // TX1 (mock): "cria" a solicitacao com um SolicitacaoId fixo, guardando o contexto
        // congelado para que carregarParaDecisao(...) possa devolve-lo sem duplicar a logica de
        // congelamento aqui no teste.
        when(solicitacoesAumentoLimitePort.registrar(any())).thenAnswer(invocation -> {
            NovaSolicitacaoAumentoLimite nova = invocation.getArgument(0);
            contextoCapturado.set(nova.contextoDecisaoCredito());
            return new SolicitacaoCriada(SOLICITACAO_ID_PADRAO);
        });

        when(solicitacoesAumentoLimitePort.carregarParaDecisao(any())).thenAnswer(invocation ->
                new CargaParaDecisao(StatusSolicitacaoAumentoLimite.SOLICITADA, contextoCapturado.get(),
                        CONTA_ID, new CorrelationId(UUID.randomUUID())));

        // TX2 (mock): aplica de fato a decisao calculada pelo MotorDecisaoCredito real -- este
        // teste nao reimplementa a politica, so traduz resultado -> status, como o adapter real faria.
        when(solicitacoesAumentoLimitePort.aplicarDecisao(any(), any(), any(), any())).thenAnswer(invocation -> {
            DecisaoCredito decisao = invocation.getArgument(1);
            StatusSolicitacaoAumentoLimite status = decisao.resultado() == ResultadoDecisaoCredito.APROVADA
                    ? StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO
                    : StatusSolicitacaoAumentoLimite.REJEITADA;
            return new ResultadoAplicacaoDecisao(true, status, decisao);
        });
    }

    // --- 201 aprovado / 201 rejeitado -----------------------------------------------------------

    @Test
    void postAprovado_devolve201_comLimitePendenteIgualAoSolicitado() throws Exception {
        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.aprovado"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO_APROVADO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contaId").value("10001"))
                .andExpect(jsonPath("$.status").value("AGUARDANDO_EFETIVACAO"))
                .andExpect(jsonPath("$.limiteChequeEspecialVigente").value(500000))
                .andExpect(jsonPath("$.limiteSolicitado").value(600000))
                .andExpect(jsonPath("$.limiteSolicitadoPendenteDeEfetivacao").value(600000))
                .andExpect(jsonPath("$.decisao.resultado").value("APROVADA"))
                .andExpect(jsonPath("$.decisao.motivo").value("DENTRO_DA_POLITICA_AUTOMATICA"))
                .andExpect(jsonPath("$.decisao.versaoPoliticaCredito").value("v1"))
                .andExpect(jsonPath("$.solicitacaoId").exists());
    }

    @Test
    void postRejeitado_devolve201_semCampoDeLimitePendente() throws Exception {
        // limiteSolicitado 1.500.000 (R$ 15.000,00) excede o teto de R$ 10.000,00 da v1 ->
        // FORA_DA_POLITICA_AUTOMATICA, mesmo com risco BAIXO e conta REGULAR.
        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.rejeitado"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO_FORA_DA_POLITICA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJEITADA"))
                .andExpect(jsonPath("$.decisao.resultado").value("REJEITADA"))
                .andExpect(jsonPath("$.decisao.motivo").value("FORA_DA_POLITICA_AUTOMATICA"))
                // doesNotExist, e nao isNull(): presenca e a pendencia (plano #0003).
                .andExpect(jsonPath("$.limiteSolicitadoPendenteDeEfetivacao").doesNotExist());
    }

    // --- 200 replay -------------------------------------------------------------------------

    @Test
    void replay_devolve200_mesmaSolicitacaoId() throws Exception {
        String idemKey = UUID.randomUUID().toString();
        AtorId originador = new AtorId("gerente.replay");

        String fingerprint = FingerprintCanonico.calcular(
                new ClienteId("1"), CONTA_ID, 600000L, 500000L, "PRESENCIAL", "cliente pediu no balcao");
        RegistroIdempotencia registro = new RegistroIdempotencia(
                originador, new IdempotencyKey(UUID.fromString(idemKey)), fingerprint,
                SOLICITACAO_ID_PADRAO, Instant.now());

        when(registroIdempotenciaPort.buscar(eq(originador), eq(new IdempotencyKey(UUID.fromString(idemKey)))))
                .thenReturn(Optional.of(registro));
        // doReturn(...).when(mock)..., e nao when(mock...).thenReturn(...): os dois metodos abaixo
        // ja tem um thenAnswer padrao (Fase @BeforeEach) que VALIDA seus argumentos -- reescrever
        // via when(mock.metodo(matchers)) IRIA EXECUTAR esse answer antigo durante o proprio setup
        // (matchers como any()/eq(x) avaliam para null/x como argumento java real da chamada de
        // sondagem do Mockito), lancando NPE antes mesmo do novo stub ser vinculado. doReturn evita
        // essa sondagem.
        doReturn(new CargaParaDecisao(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO,
                        contextoAprovado(), CONTA_ID, new CorrelationId(UUID.randomUUID())))
                .when(solicitacoesAumentoLimitePort).carregarParaDecisao(SOLICITACAO_ID_PADRAO);
        doReturn(new ResultadoAplicacaoDecisao(false, StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO,
                        decisaoAprovada()))
                .when(solicitacoesAumentoLimitePort).aplicarDecisao(eq(SOLICITACAO_ID_PADRAO), any(), any(), any());

        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.replay"))
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO_APROVADO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.solicitacaoId").value(SOLICITACAO_ID_PADRAO.valor().toString()));
    }

    // --- 400: taxonomia de parsing -------------------------------------------------------------

    @Test
    void semHeaderIdempotencyKey_e400ComCodigoIdempotencyKeyAusente() throws Exception {
        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.sem.header"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO_APROVADO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("IDEMPOTENCY_KEY_AUSENTE"));
    }

    @Test
    void headerIdempotencyKeyNaoUuid_e400ComCodigoIdempotencyKeyInvalida() throws Exception {
        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.header.invalido"))
                        .header("Idempotency-Key", "nao-e-um-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO_APROVADO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("IDEMPOTENCY_KEY_INVALIDA"));
    }

    @Test
    void limiteSolicitadoFracionario_e400ComCodigoComandoIlegivel() throws Exception {
        // accept-float-as-int: false (application.yml) -- 600000.5 precisa falhar na
        // desserializacao, nunca ser truncado silenciosamente para 600000.
        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.fracionario"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"limiteSolicitado":600000.5,"limiteVigenteVisto":500000,
                                 "manifestacaoCliente":{"canalManifestacao":"PRESENCIAL","observacao":"x"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("COMANDO_ILEGIVEL"));
    }

    @Test
    void limiteSolicitadoNaoNumerico_e400ComCodigoComandoIlegivel() throws Exception {
        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.nao.numerico"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"limiteSolicitado":"abc","limiteVigenteVisto":500000,
                                 "manifestacaoCliente":{"canalManifestacao":"PRESENCIAL","observacao":"x"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("COMANDO_ILEGIVEL"));
    }

    // --- 403 / 404 (ja existentes de #0002, agora com codigo) -----------------------------------

    @Test
    void semDireitoDeAtendimento_e403ComCodigo_eNenhumaChamadaAoCoreLegado() throws Exception {
        dependenciasExternas.stubFor(WireMock.get(urlEqualTo(PATH_DIREITO))
                .willReturn(aResponse().withStatus(403)));

        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.sem.direito"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO_APROVADO))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SEM_DIREITO_DE_ATENDIMENTO"));

        dependenciasExternas.verify(0, postRequestedFor(urlEqualTo(PATH_CORE)));
    }

    @Test
    void contaNaoEncontradaNoCore_e404ComCodigo() throws Exception {
        dependenciasExternas.stubFor(WireMock.post(urlEqualTo(PATH_CORE)).willReturn(json("""
                {"codRet":"121","msgRet":"CONTA NAO ENCONTRADA"}
                """)));

        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.conta.404"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO_APROVADO))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("CONTA_NAO_ENCONTRADA"));
    }

    // --- 409 ------------------------------------------------------------------------------------

    @Test
    void limiteVigenteDesatualizado_e409ComCodigo() throws Exception {
        // Caso decisivo da spec: visto 400.000, Core em 500.000, pedido de 600.000 -> 409, nunca 422.
        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.stale"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"limiteSolicitado":600000,"limiteVigenteVisto":400000,
                                 "manifestacaoCliente":{"canalManifestacao":"PRESENCIAL","observacao":"x"}}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("LIMITE_VIGENTE_DESATUALIZADO"));
    }

    @Test
    void solicitacaoNaoTerminalExistente_e409ComCodigo() throws Exception {
        doReturn(new SolicitacaoNaoTerminalExistente()).when(solicitacoesAumentoLimitePort).registrar(any());

        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.nao.terminal"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO_APROVADO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("SOLICITACAO_NAO_TERMINAL_EXISTENTE"));
    }

    @Test
    void idempotenciaEmProcessamento_e409ComCodigo() throws Exception {
        doThrow(new IdempotenciaEmProcessamentoException("lock indisponivel (FOR UPDATE NOWAIT)"))
                .when(solicitacoesAumentoLimitePort).aplicarDecisao(any(), any(), any(), any());

        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.em.processamento"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO_APROVADO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("IDEMPOTENCIA_EM_PROCESSAMENTO"));
    }

    // --- 422 ------------------------------------------------------------------------------------

    @Test
    void limiteSolicitadoNaoPositivo_e422ComCodigoComandoInvalido() throws Exception {
        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.nao.positivo"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"limiteSolicitado":0,"limiteVigenteVisto":500000,
                                 "manifestacaoCliente":{"canalManifestacao":"PRESENCIAL","observacao":"x"}}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.codigo").value("COMANDO_INVALIDO"));
    }

    @Test
    void limiteSolicitadoNaoAumenta_e422ComCodigo() throws Exception {
        // limiteVigenteVisto bate com o vigente do Core (500000) -- entao passa o stale check --
        // mas limiteSolicitado igual ao vigente nao aumenta.
        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.nao.aumenta"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"limiteSolicitado":500000,"limiteVigenteVisto":500000,
                                 "manifestacaoCliente":{"canalManifestacao":"PRESENCIAL","observacao":"x"}}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.codigo").value("LIMITE_SOLICITADO_NAO_AUMENTA"));
    }

    @Test
    void idempotenciaFingerprintDivergente_e422ComCodigo() throws Exception {
        String idemKey = UUID.randomUUID().toString();
        AtorId originador = new AtorId("gerente.fingerprint");
        RegistroIdempotencia registroComOutroFingerprint = new RegistroIdempotencia(
                originador, new IdempotencyKey(UUID.fromString(idemKey)), "fingerprint-gravado-diferente",
                SOLICITACAO_ID_PADRAO, Instant.now());

        when(registroIdempotenciaPort.buscar(eq(originador), eq(new IdempotencyKey(UUID.fromString(idemKey)))))
                .thenReturn(Optional.of(registroComOutroFingerprint));

        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.fingerprint"))
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO_APROVADO))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.codigo").value("IDEMPOTENCIA_FINGERPRINT_DIVERGENTE"));
    }

    // --- 502 / 503 / 504 --------------------------------------------------------------------

    @Test
    void coreLegadoIndisponivel_e503ComCodigo() throws Exception {
        dependenciasExternas.stubFor(WireMock.post(urlEqualTo(PATH_CORE)).willReturn(aResponse().withStatus(503)));

        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.core.503"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO_APROVADO))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("DEPENDENCIA_INDISPONIVEL"));
    }

    @Test
    void coreLegadoRespostaIlegivel_e502ComCodigo() throws Exception {
        dependenciasExternas.stubFor(WireMock.post(urlEqualTo(PATH_CORE)).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"codRet\":\"000\",")));

        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.core.502"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO_APROVADO))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.codigo").value("DEPENDENCIA_INDISPONIVEL"));
    }

    @Test
    void coreLegadoTimeout_e504ComCodigo() throws Exception {
        dependenciasExternas.stubFor(WireMock.post(urlEqualTo(PATH_CORE)).willReturn(aResponse()
                .withStatus(200).withFixedDelay(6000)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"codRet\":\"121\"}")));

        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.core.504"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO_APROVADO))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.codigo").value("DEPENDENCIA_INDISPONIVEL"));
    }

    // --- Scope cruzado (least privilege por operacao) -------------------------------------------

    @Test
    void postComTokenSoDeLeitura_e403() throws Exception {
        // JwtTestSupport.validToken carrega credito.leitura, NUNCA credito.escrita.
        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + JwtTestSupport.validToken(
                                "gerente.so.leitura", AUD, java.util.List.of("GERENTE_RELACIONAMENTO")))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO_APROVADO))
                .andExpect(status().isForbidden());
    }

    @Test
    void getComTokenSoDeEscrita_e403() throws Exception {
        // A rota antiga (GET) nao ganhou o scope novo: um token so com credito.escrita continua
        // recusado ali.
        mockMvc.perform(get(ENDPOINT_GET)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.so.escrita")))
                .andExpect(status().isForbidden());
    }

    // --- AC27: corpo com clienteId/origem extra nao altera o gravado -----------------------------

    @Test
    void ac27_corpoComClienteIdEOrigemExtra_naoAlteraOQueEGravado() throws Exception {
        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.ac27"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"limiteSolicitado":600000,"limiteVigenteVisto":500000,
                                 "clienteId":"999999","contaId":"888888","origemSolicitacao":"GERENTE",
                                 "manifestacaoCliente":{"canalManifestacao":"PRESENCIAL","observacao":"x"}}
                                """))
                .andExpect(status().isCreated());

        ArgumentCaptor<NovaSolicitacaoAumentoLimite> captor = ArgumentCaptor.forClass(NovaSolicitacaoAumentoLimite.class);
        verify(solicitacoesAumentoLimitePort).registrar(captor.capture());

        assertThat(captor.getValue().clienteId()).isEqualTo(new ClienteId("1"));
        assertThat(captor.getValue().contaId()).isEqualTo(CONTA_ID);
        assertThat(captor.getValue().origemSolicitacao()).isEqualTo(OrigemSolicitacao.CLIENTE);
    }

    // --- GUARDRAIL DE AUTORIA: originadorId vem SEMPRE do JWT, nunca do corpo --------------------

    /**
     * O teste mais importante desta etapa (requisito explicito do usuario, alem do plano): mesmo
     * que alguem adicionasse um campo {@code originadorId} ao JSON tentando forjar autoria de
     * outro gerente, ele e ignorado -- {@link com.fkmanager360.credito.adapter.in.web
     * .SolicitacaoAumentoLimiteRequest} nem declara esse campo, e o Jackson padrao (sem
     * {@code FAIL_ON_UNKNOWN_PROPERTIES}) simplesmente descarta propriedades desconhecidas. O
     * {@code AtorId} efetivamente usado -- e portanto o namespace de idempotencia -- precisa ser
     * exclusivamente {@code jwt.getSubject()}.
     */
    @Test
    void guardrailDeAutoria_originadorIdVemSempreDoJwt_naoDoCorpo() throws Exception {
        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.a"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"limiteSolicitado":600000,"limiteVigenteVisto":500000,
                                 "originadorId":"gerente.b",
                                 "manifestacaoCliente":{"canalManifestacao":"PRESENCIAL","observacao":"x"}}
                                """))
                .andExpect(status().isCreated());

        ArgumentCaptor<NovaSolicitacaoAumentoLimite> captor = ArgumentCaptor.forClass(NovaSolicitacaoAumentoLimite.class);
        verify(solicitacoesAumentoLimitePort).registrar(captor.capture());

        assertThat(captor.getValue().originadorId().valor())
                .as("originadorId tem que vir do JWT (sub), nunca do corpo -- e a base da autoria e do namespace de idempotencia")
                .isEqualTo("gerente.a")
                .isNotEqualTo("gerente.b");
    }

    // --- Metrica: conta decisoes, nao respostas ---------------------------------------------

    @Test
    void metrica_incrementaSoQuandoDecidiuAgora() throws Exception {
        String idemKey = UUID.randomUUID().toString();
        double antes = totalDecisoesCredito();

        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.metrica"))
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO_APROVADO))
                .andExpect(status().isCreated());

        double depoisDaPrimeira = totalDecisoesCredito();
        assertThat(depoisDaPrimeira - antes)
                .as("uma decisao nova (decidiuAgora=true) precisa incrementar a metrica em exatamente 1")
                .isEqualTo(1.0);

        // Segunda submissao com a MESMA Idempotency-Key: o pre-check encontra o registro (mesmo
        // fingerprint) e o replay NAO recalcula/repersiste decisao -- decidiuAgora=false.
        AtorId originador = new AtorId("gerente.metrica");
        String fingerprint = FingerprintCanonico.calcular(
                new ClienteId("1"), CONTA_ID, 600000L, 500000L, "PRESENCIAL", "cliente pediu no balcao");
        RegistroIdempotencia registro = new RegistroIdempotencia(
                originador, new IdempotencyKey(UUID.fromString(idemKey)), fingerprint,
                SOLICITACAO_ID_PADRAO, Instant.now());

        when(registroIdempotenciaPort.buscar(eq(originador), eq(new IdempotencyKey(UUID.fromString(idemKey)))))
                .thenReturn(Optional.of(registro));
        // doReturn(...).when(mock)... pelo mesmo motivo documentado em replay_devolve200_...:
        // reescrever via when(mock.metodo(matchers)) executaria o thenAnswer padrao do @BeforeEach
        // durante o proprio setup.
        doReturn(new CargaParaDecisao(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO,
                        contextoAprovado(), CONTA_ID, new CorrelationId(UUID.randomUUID())))
                .when(solicitacoesAumentoLimitePort).carregarParaDecisao(SOLICITACAO_ID_PADRAO);
        doReturn(new ResultadoAplicacaoDecisao(false, StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO,
                        decisaoAprovada()))
                .when(solicitacoesAumentoLimitePort).aplicarDecisao(eq(SOLICITACAO_ID_PADRAO), any(), any(), any());

        mockMvc.perform(post(ENDPOINT_POST)
                        .header("Authorization", "Bearer " + tokenParaEscrita("gerente.metrica"))
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO_APROVADO))
                .andExpect(status().isOk());

        assertThat(totalDecisoesCredito())
                .as("replay puro (decidiuAgora=false) nao pode incrementar a metrica de novo")
                .isEqualTo(depoisDaPrimeira);
    }

    // --- Helpers -----------------------------------------------------------------------------

    private double totalDecisoesCredito() {
        return meterRegistry.find("decisoes_credito_total").counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    private static ContextoDecisaoCredito contextoAprovado() {
        DadosCreditoCore dados = new DadosCreditoCore(
                new LimiteChequeEspecialVigente(500_000L), SituacaoConta.REGULAR,
                ClassificacaoRiscoCreditoBase.BAIXO, Instant.now(), "CoreLegado");
        return ContextoDecisaoCredito.congelar(
                dados, new LimiteSolicitado(600_000L), new VersaoPoliticaCredito("v1"), Instant.now());
    }

    private static DecisaoCredito decisaoAprovada() {
        return new DecisaoCredito(
                ResultadoDecisaoCredito.APROVADA, MotivoDecisaoCredito.DENTRO_DA_POLITICA_AUTOMATICA,
                new VersaoPoliticaCredito("v1"), Instant.now(), AtorSistema.MOTOR_DECISAO_CREDITO);
    }

    private String tokenParaEscrita(String sub) {
        return JwtTestSupport.tokenComEscritaDeCredito(sub, AUD);
    }

    private static String tokenDelegado() {
        try {
            com.nimbusds.jwt.JWTClaimsSet claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                    .subject("gerente.a")
                    .audience("servico-carteira-clientes")
                    .issueTime(java.util.Date.from(Instant.now().minusSeconds(5)))
                    .expirationTime(java.util.Date.from(Instant.now().plusSeconds(300)))
                    .claim("scope", "carteira.leitura")
                    .claim("papeis", java.util.List.of("GERENTE_RELACIONAMENTO"))
                    .build();

            com.nimbusds.jwt.SignedJWT jwt = new com.nimbusds.jwt.SignedJWT(
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
