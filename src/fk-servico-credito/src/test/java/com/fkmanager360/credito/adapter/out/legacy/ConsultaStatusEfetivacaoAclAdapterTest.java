package com.fkmanager360.credito.adapter.out.legacy;

import com.fkmanager360.credito.application.port.out.ResultadoConsultaStatusCore;
import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.MotivoFalhaEfetivacao;
import com.fkmanager360.credito.domain.ProtocoloCore;
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
 * S4 (ADR-0018): a ACL de consulta de status (#0006) provada contra um mock HTTP server real, com
 * o mesmo RestClient e adapter de producao. Cobre a matriz da taxonomia -- processada, em
 * processamento, desconhecida, os quatro definitivos (nunca emitidos por este simulador, mas o
 * contrato os preve, #0006, Javadoc de {@code ConsultaStatusEfetivacaoLegadoResponse}), e a mesma
 * "regra de ouro" do Owner: erro HTTP tecnico nunca produz FalhaDefinitiva pelo status.
 */
class ConsultaStatusEfetivacaoAclAdapterTest {

    private static final String PATH = "/legado/efetivacoes/consulta";
    private static final EfetivacaoId EFETIVACAO_ID = new EfetivacaoId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final ProtocoloCore PROTOCOLO = new ProtocoloCore("000000000001");

    private WireMockServer wireMock;
    private ConsultaStatusEfetivacaoAclAdapter adapter;

    @BeforeEach
    void subirMockServerEAdapter() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());

        // Read-timeout mais folgado que o analogo em EfetivacaoLegadoAclAdapterTest (500ms):
        // observado empiricamente flakando neste ambiente sob execucao repetida (Docker-outside-
        // of-Docker, contencao de CPU entre muitos ciclos start/stop de WireMockServer por classe).
        // 1200ms continua com margem segura abaixo do fixedDelay de 2000ms do teste de timeout.
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(1));
        requestFactory.setReadTimeout(Duration.ofMillis(1200));

        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost:" + wireMock.port())
                .requestFactory(requestFactory)
                .build();

        adapter = new ConsultaStatusEfetivacaoAclAdapter(restClient);
    }

    @AfterEach
    void pararMockServer() {
        wireMock.stop();
    }

    // --- Por EfetivacaoId vs por ProtocoloCore --------------------------------------------------

    @Test
    void consultarPorEfetivacaoId_enviaSomenteIdEft() {
        stub(codRet("301", null, null));

        adapter.consultarPorEfetivacaoId(EFETIVACAO_ID);

        wireMock.verify(WireMock.postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(equalToJson("""
                        {"idEft":"11111111-1111-1111-1111-111111111111","numPrt":null}
                        """)));
    }

    @Test
    void consultarPorProtocolo_enviaSomenteNumPrt() {
        stub(codRet("301", null, null));

        adapter.consultarPorProtocolo(PROTOCOLO);

        wireMock.verify(WireMock.postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(equalToJson("""
                        {"idEft":null,"numPrt":"000000000001"}
                        """)));
    }

    // --- Processada ------------------------------------------------------------------------------

    @Test
    void processada_comNumPrtEVlrLimEft_traduzParaEfetivada() {
        stub(codRet("000", "000000000001", "000000000600000"));

        ResultadoConsultaStatusCore resultado = adapter.consultarPorEfetivacaoId(EFETIVACAO_ID);

        assertThat(resultado).isEqualTo(new ResultadoConsultaStatusCore.Efetivada(PROTOCOLO, 600_000L));
    }

    @Test
    void processada_semVlrLimEft_eIndeterminada() {
        stub(codRet("000", "000000000001", null));

        assertThat(adapter.consultarPorEfetivacaoId(EFETIVACAO_ID)).isInstanceOf(ResultadoConsultaStatusCore.Indeterminada.class);
    }

    @Test
    void processada_vlrLimEftIlegivel_eIndeterminada() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(json("""
                {"codRet":"000","msgRet":"?","idEft":"11111111-1111-1111-1111-111111111111",
                 "numPrt":"000000000001","vlrLimEft":"abc"}
                """)));

        assertThat(adapter.consultarPorEfetivacaoId(EFETIVACAO_ID)).isInstanceOf(ResultadoConsultaStatusCore.Indeterminada.class);
    }

    /**
     * Achado do /code-review (#0006): {@code vlrLimEft="0"} (ou negativo) analisa como {@code long}
     * valido, mas violaria o invariante do compact constructor de {@code Efetivada}
     * ({@code limiteEfetivadoCentavos} deve ser positivo) -- sem esta checagem explicita, a
     * {@code IllegalArgumentException} escaparia desta ACL, quebrando o contrato "nunca lanca
     * excecao para o chamador".
     */
    @Test
    void processada_vlrLimEftNaoPositivo_eIndeterminada() {
        stub(codRet("000", "000000000001", "0"));

        assertThat(adapter.consultarPorEfetivacaoId(EFETIVACAO_ID)).isInstanceOf(ResultadoConsultaStatusCore.Indeterminada.class);
    }

    // --- Em processamento / desconhecida ----------------------------------------------------------

    @Test
    void emProcessamento_301() {
        stub(codRet("301", "000000000001", null));

        assertThat(adapter.consultarPorEfetivacaoId(EFETIVACAO_ID)).isInstanceOf(ResultadoConsultaStatusCore.EmProcessamento.class);
    }

    @Test
    void desconhecida_404() {
        stub(codRet("404", null, null));

        assertThat(adapter.consultarPorEfetivacaoId(EFETIVACAO_ID)).isInstanceOf(ResultadoConsultaStatusCore.Desconhecida.class);
    }

    // --- Definitivo (nunca emitido por este simulador -- contrato preve para completude) --------

    @Test
    void limiteVigenteDivergente_205_eFalhaDefinitiva() {
        stub(codRet("205", null, null));

        assertThat(adapter.consultarPorEfetivacaoId(EFETIVACAO_ID))
                .isEqualTo(new ResultadoConsultaStatusCore.FalhaDefinitiva(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE));
    }

    @Test
    void contaNaoEncontrada_121_eFalhaDefinitiva() {
        stub(codRet("121", null, null));

        assertThat(adapter.consultarPorEfetivacaoId(EFETIVACAO_ID))
                .isEqualTo(new ResultadoConsultaStatusCore.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_INEXISTENTE));
    }

    @Test
    void contaBloqueada_118_eFalhaDefinitiva() {
        stub(codRet("118", null, null));

        assertThat(adapter.consultarPorEfetivacaoId(EFETIVACAO_ID))
                .isEqualTo(new ResultadoConsultaStatusCore.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_BLOQUEADA_NA_EFETIVACAO));
    }

    @Test
    void instrucaoInvalida_199_eFalhaDefinitiva() {
        stub(codRet("199", null, null));

        assertThat(adapter.consultarPorEfetivacaoId(EFETIVACAO_ID))
                .isEqualTo(new ResultadoConsultaStatusCore.FalhaDefinitiva(MotivoFalhaEfetivacao.INSTRUCAO_INVALIDA));
    }

    // --- Indeterminada: transporte, HTTP, payload -------------------------------------------------

    @Test
    void erroDeServidor_5xx_eIndeterminada_nuncaDefinitiva() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        assertThat(adapter.consultarPorEfetivacaoId(EFETIVACAO_ID)).isInstanceOf(ResultadoConsultaStatusCore.Indeterminada.class);
    }

    @Test
    void http4xxInesperado_eIndeterminada_nuncaDefinitiva() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400)));

        assertThat(adapter.consultarPorEfetivacaoId(EFETIVACAO_ID)).isInstanceOf(ResultadoConsultaStatusCore.Indeterminada.class);
    }

    @Test
    void timeout_eIndeterminada() {
        wireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withFixedDelay(2000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"codRet\":\"000\",\"numPrt\":\"000000000001\",\"vlrLimEft\":\"000000000600000\"}")));

        assertThat(adapter.consultarPorEfetivacaoId(EFETIVACAO_ID)).isInstanceOf(ResultadoConsultaStatusCore.Indeterminada.class);
    }

    @Test
    void conexaoInterrompida_eIndeterminada() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThat(adapter.consultarPorEfetivacaoId(EFETIVACAO_ID)).isInstanceOf(ResultadoConsultaStatusCore.Indeterminada.class);
    }

    @Test
    void codRetDesconhecido_eIndeterminada() {
        stub(codRet("777", null, null));

        assertThat(adapter.consultarPorEfetivacaoId(EFETIVACAO_ID)).isInstanceOf(ResultadoConsultaStatusCore.Indeterminada.class);
    }

    @Test
    void payloadMalformado_eIndeterminada() {
        wireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"codRet\":\"000\",")));

        assertThat(adapter.consultarPorEfetivacaoId(EFETIVACAO_ID)).isInstanceOf(ResultadoConsultaStatusCore.Indeterminada.class);
    }

    @Test
    void respostaSemCodRet_eIndeterminada() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(json("{\"msgRet\":\"?\"}")));

        assertThat(adapter.consultarPorEfetivacaoId(EFETIVACAO_ID)).isInstanceOf(ResultadoConsultaStatusCore.Indeterminada.class);
    }

    // --- Helpers -----------------------------------------------------------------------------

    private static String codRet(String codRet, String numPrt, String vlrLimEft) {
        return """
                {"codRet":"%s","msgRet":"?","idEft":"11111111-1111-1111-1111-111111111111",
                 "numPrt":%s,"vlrLimEft":%s}
                """.formatted(
                codRet,
                numPrt == null ? "null" : "\"" + numPrt + "\"",
                vlrLimEft == null ? "null" : "\"" + vlrLimEft + "\"");
    }

    private void stub(String corpo) {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(json(corpo)));
    }

    private static ResponseDefinitionBuilder json(String corpo) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(corpo);
    }
}
