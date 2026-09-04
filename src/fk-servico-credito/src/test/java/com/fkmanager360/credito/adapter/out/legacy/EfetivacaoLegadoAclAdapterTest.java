package com.fkmanager360.credito.adapter.out.legacy;

import com.fkmanager360.credito.application.port.out.IntencaoEfetivacao;
import com.fkmanager360.credito.application.port.out.ResultadoInstrucaoCore;
import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.CorrelationId;
import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.LimiteChequeEspecialVigente;
import com.fkmanager360.credito.domain.LimiteSolicitado;
import com.fkmanager360.credito.domain.MotivoFalhaEfetivacao;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4 (ADR-0018): a ACL de efetivacao provada contra um mock HTTP server real, com o mesmo
 * RestClient e adapter de producao. Cobre a matriz completa da taxonomia de quatro classes (plano
 * #0004, secao 6), incluindo a regra de ouro do Owner: erro HTTP tecnico nunca produz
 * FalhaDefinitiva pelo status.
 */
class EfetivacaoLegadoAclAdapterTest {

    private static final String PATH = "/legado/efetivacoes";
    private static final EfetivacaoId EFETIVACAO_ID = new EfetivacaoId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final CorrelationId CORRELATION_ID = new CorrelationId(UUID.fromString("22222222-2222-2222-2222-222222222222"));

    private final IntencaoEfetivacao intencao = new IntencaoEfetivacao(
            EFETIVACAO_ID, UUID.randomUUID(), new ContaId("10001"),
            new LimiteChequeEspecialVigente(500_000), new LimiteSolicitado(600_000), CORRELATION_ID);

    private WireMockServer wireMock;
    private EfetivacaoLegadoAclAdapter adapter;

    @BeforeEach
    void subirMockServerEAdapter() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());

        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(1));
        requestFactory.setReadTimeout(Duration.ofMillis(500));

        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost:" + wireMock.port())
                .requestFactory(requestFactory)
                .build();

        adapter = new EfetivacaoLegadoAclAdapter(restClient);
    }

    @AfterEach
    void pararMockServer() {
        wireMock.stop();
    }

    // --- Aceite ------------------------------------------------------------------------------

    @Test
    void aceite_comNumPrt_traduzParaProtocoloCore() {
        stub(codRet("000", "PRT-000000000001"));

        ResultadoInstrucaoCore resultado = adapter.entregar(intencao);

        assertThat(resultado).isInstanceOf(ResultadoInstrucaoCore.Aceite.class);
        assertThat(((ResultadoInstrucaoCore.Aceite) resultado).protocoloCore().valor()).isEqualTo("PRT-000000000001");
    }

    @Test
    void request_enviaIdentidadesPorExtensoEValoresHostFormatados() {
        stub(codRet("000", "PRT-000000000001"));

        adapter.entregar(intencao);

        wireMock.verify(WireMock.postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(equalToJson("""
                        {"idEft":"11111111-1111-1111-1111-111111111111",
                         "numCta":"0000010001",
                         "vlrLimChqEspEsp":"000000000500000",
                         "vlrLimNov":"000000000600000",
                         "idCor":"22222222-2222-2222-2222-222222222222"}
                        """)));
    }

    @Test
    void aceite_semNumPrt_eIndeterminado() {
        stub(codRet("000", null));

        ResultadoInstrucaoCore resultado = adapter.entregar(intencao);

        assertThat(resultado).isInstanceOf(ResultadoInstrucaoCore.RespostaIndeterminada.class);
    }

    // --- Definitivo ----------------------------------------------------------------------------

    @Test
    void limiteVigenteDivergente_205_eFalhaDefinitiva() {
        stub(codRet("205", null));

        ResultadoInstrucaoCore resultado = adapter.entregar(intencao);

        assertThat(resultado).isEqualTo(new ResultadoInstrucaoCore.FalhaDefinitiva(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE));
    }

    @Test
    void contaNaoEncontrada_121_eFalhaDefinitiva() {
        stub(codRet("121", null));

        ResultadoInstrucaoCore resultado = adapter.entregar(intencao);

        assertThat(resultado).isEqualTo(new ResultadoInstrucaoCore.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_INEXISTENTE));
    }

    @Test
    void contaBloqueada_118_eFalhaDefinitiva() {
        stub(codRet("118", null));

        ResultadoInstrucaoCore resultado = adapter.entregar(intencao);

        assertThat(resultado).isEqualTo(new ResultadoInstrucaoCore.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_BLOQUEADA_NA_EFETIVACAO));
    }

    @Test
    void instrucaoInvalida_199_eFalhaDefinitiva() {
        stub(codRet("199", null));

        ResultadoInstrucaoCore resultado = adapter.entregar(intencao);

        assertThat(resultado).isEqualTo(new ResultadoInstrucaoCore.FalhaDefinitiva(MotivoFalhaEfetivacao.INSTRUCAO_INVALIDA));
    }

    // --- Transitorio -----------------------------------------------------------------------------

    @Test
    void indisponibilidade_998_eTransitoria() {
        stub(codRet("998", null));

        assertThat(adapter.entregar(intencao)).isInstanceOf(ResultadoInstrucaoCore.FalhaTransitoria.class);
    }

    @Test
    void http429_eTransitoria() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(429)));

        assertThat(adapter.entregar(intencao)).isInstanceOf(ResultadoInstrucaoCore.FalhaTransitoria.class);
    }

    @Test
    void erroDeServidor_5xx_eTransitoria() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        assertThat(adapter.entregar(intencao)).isInstanceOf(ResultadoInstrucaoCore.FalhaTransitoria.class);
    }

    @Test
    void timeout_eTransitoria() {
        wireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withFixedDelay(2000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"codRet\":\"000\",\"numPrt\":\"PRT-000000000001\"}")));

        assertThat(adapter.entregar(intencao)).isInstanceOf(ResultadoInstrucaoCore.FalhaTransitoria.class);
    }

    @Test
    void conexaoInterrompida_eTransitoria() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThat(adapter.entregar(intencao)).isInstanceOf(ResultadoInstrucaoCore.FalhaTransitoria.class);
    }

    // --- Indeterminado (regra de ouro: 4xx inesperado NUNCA e definitivo) ----------------------

    @Test
    void payloadIncompativelParaEfetivacaoIdExistente_207_eIndeterminado() {
        stub(codRet("207", null));

        assertThat(adapter.entregar(intencao)).isInstanceOf(ResultadoInstrucaoCore.RespostaIndeterminada.class);
    }

    @Test
    void codRetDesconhecido_eIndeterminado() {
        stub(codRet("777", null));

        assertThat(adapter.entregar(intencao)).isInstanceOf(ResultadoInstrucaoCore.RespostaIndeterminada.class);
    }

    @Test
    void respostaSemCodRet_eIndeterminado() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(json("{\"msgRet\":\"?\"}")));

        assertThat(adapter.entregar(intencao)).isInstanceOf(ResultadoInstrucaoCore.RespostaIndeterminada.class);
    }

    @Test
    void payloadMalformado_eIndeterminado() {
        wireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"codRet\":\"000\",")));

        assertThat(adapter.entregar(intencao)).isInstanceOf(ResultadoInstrucaoCore.RespostaIndeterminada.class);
    }

    @Test
    void http4xxInesperado_semSemanticaDefinida_eIndeterminado_nuncaDefinitivo() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400)));

        assertThat(adapter.entregar(intencao)).isInstanceOf(ResultadoInstrucaoCore.RespostaIndeterminada.class);
    }

    @Test
    void contentTypeIncompativel_eIndeterminado() {
        wireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain")
                        .withBody("codRet=000")));

        assertThat(adapter.entregar(intencao)).isInstanceOf(ResultadoInstrucaoCore.RespostaIndeterminada.class);
    }

    // --- Helpers -----------------------------------------------------------------------------

    private static String codRet(String codRet, String numPrt) {
        return """
                {"codRet":"%s","msgRet":"?","idEft":"11111111-1111-1111-1111-111111111111",
                 "numPrt":%s,"idCor":"22222222-2222-2222-2222-222222222222"}
                """.formatted(codRet, numPrt == null ? "null" : "\"" + numPrt + "\"");
    }

    private void stub(String corpo) {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(json(corpo)));
    }

    private static ResponseDefinitionBuilder json(String corpo) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(corpo);
    }
}
