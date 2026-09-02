package com.fkmanager360.credito.adapter.out.legacy;

import com.fkmanager360.credito.application.port.out.CoreLegadoTimeoutException;
import com.fkmanager360.credito.application.port.out.CoreLegadoUnavailableException;
import com.fkmanager360.credito.application.port.out.InvalidCoreLegadoResponseException;
import com.fkmanager360.credito.domain.ClassificacaoRiscoCreditoBase;
import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.DadosCreditoCore;
import com.fkmanager360.credito.domain.SituacaoConta;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S4: a ACL de Credito provada contra um mock HTTP server real, com o mesmo RestClient e adapter
 * de producao (ADR-0018). O simulador real e bem comportado; e aqui que a robustez do adapter e
 * de fato provada.
 */
class CreditoLegadoAclAdapterTest {

    private static final String PATH = "/legado/contas/consulta-credito";
    private static final ContaId CONTA = new ContaId("10001");

    /** Relogio fixo em 2026, deliberadamente distante das datas de host usadas nos stubs. */
    private static final Instant AGORA = Instant.parse("2026-09-02T16:00:00Z");

    private WireMockServer wireMock;
    private CreditoLegadoAclAdapter adapter;

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

        adapter = new CreditoLegadoAclAdapter(restClient, Clock.fixed(AGORA, ZoneOffset.UTC));
    }

    @AfterEach
    void pararMockServer() {
        wireMock.stop();
    }

    // --- Traducao do contrato host-centric ---------------------------------------------------

    @Test
    void sucesso_traduzLimiteSituacaoEClassificacaoDeRisco() {
        stubSucesso("000000000500000", "01", "1", "20260115");

        DadosCreditoCore dados = adapter.consultar(CONTA).orElseThrow();

        assertThat(dados.limiteChequeEspecialVigente().centavos()).isEqualTo(500_000);
        assertThat(dados.situacaoConta()).isEqualTo(SituacaoConta.REGULAR);
        assertThat(dados.classificacaoRiscoCreditoBase()).isEqualTo(ClassificacaoRiscoCreditoBase.BAIXO);
        assertThat(dados.fonte()).isEqualTo("CoreLegado");
    }

    @Test
    void request_enviaNumeroDaContaComZeroPaddingDeDezDigitos() {
        stubSucesso("000000000500000", "01", "1", "20260115");

        adapter.consultar(CONTA);

        wireMock.verify(WireMock.postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(equalToJson("""
                        {"numCta":"0000010001"}
                        """)));
    }

    @Test
    void zeroPaddingDoValor_naoAlteraOMontanteEmCentavos() {
        stubSucesso("000000000000001", "01", "2", "20260115");

        assertThat(adapter.consultar(CONTA).orElseThrow().limiteChequeEspecialVigente().centavos())
                .isEqualTo(1);
    }

    @Test
    void limiteZero_eLimiteValido_naoAusencia() {
        stubSucesso("000000000000000", "01", "1", "20260115");

        assertThat(adapter.consultar(CONTA).orElseThrow().limiteChequeEspecialVigente().centavos())
                .isZero();
    }

    @Test
    void situacaoDiferenteDeRegular_viraIrregular_semVazarOCodigoDoHost() {
        stubSucesso("000000000500000", "02", "1", "20260115");

        assertThat(adapter.consultar(CONTA).orElseThrow().situacaoConta())
                .isEqualTo(SituacaoConta.IRREGULAR);
    }

    @Test
    void situacaoDesconhecidaDoHost_naoViraRegular() {
        // Fail-safe na direcao certa: um codigo que o host passe a emitir amanha nao pode ser
        // silenciosamente tratado como conta regular.
        stubSucesso("000000000500000", "77", "1", "20260115");

        assertThat(adapter.consultar(CONTA).orElseThrow().situacaoConta())
                .isEqualTo(SituacaoConta.IRREGULAR);
    }

    @Test
    void classificacaoDeRisco_traduzOsTresCodigosDoHost() {
        stubSucesso("000000000500000", "01", "2", "20260115");
        assertThat(adapter.consultar(CONTA).orElseThrow().classificacaoRiscoCreditoBase())
                .isEqualTo(ClassificacaoRiscoCreditoBase.MEDIO);

        stubSucesso("000000000500000", "01", "3", "20260115");
        assertThat(adapter.consultar(CONTA).orElseThrow().classificacaoRiscoCreditoBase())
                .isEqualTo(ClassificacaoRiscoCreditoBase.ALTO);
    }

    @Test
    void classificacaoDeRiscoDesconhecida_naoEInventada_viraRespostaInvalida() {
        stubSucesso("000000000500000", "01", "9", "20260115");

        assertThatThrownBy(() -> adapter.consultar(CONTA))
                .isInstanceOf(InvalidCoreLegadoResponseException.class)
                .hasMessageContaining("9");
    }

    // --- consultadoEm NAO e datAtuLim --------------------------------------------------------

    @Test
    void consultadoEm_eOInstanteDaCapturaPelaPlataforma_nuncaADataDeAtualizacaoDoHost() {
        // O host diz que mexeu no limite em 2020; a consulta acontece em 2026. As duas coisas
        // sao diferentes, e a procedencia registra a segunda.
        stubSucesso("000000000500000", "01", "1", "20200101");

        DadosCreditoCore dados = adapter.consultar(CONTA).orElseThrow();

        assertThat(dados.consultadoEm()).isEqualTo(AGORA);
        assertThat(dados.consultadoEm().atZone(ZoneOffset.UTC).getYear())
                .as("consultadoEm nao pode ser derivado de datAtuLim")
                .isEqualTo(2026);
    }

    @Test
    void consultadoEm_naoVariaComADataDeAtualizacaoDoHost() {
        stubSucesso("000000000500000", "01", "1", "20200101");
        Instant primeira = adapter.consultar(CONTA).orElseThrow().consultadoEm();

        stubSucesso("000000000500000", "01", "1", "20260228");
        Instant segunda = adapter.consultar(CONTA).orElseThrow().consultadoEm();

        assertThat(primeira).isEqualTo(segunda).isEqualTo(AGORA);
    }

    @Test
    void dataDeAtualizacaoDoHost_naoAtravessaAFronteiraDaAcl() {
        stubSucesso("000000000500000", "01", "1", "20200101");

        DadosCreditoCore dados = adapter.consultar(CONTA).orElseThrow();

        // O record de dominio nao tem onde guardar datAtuLim, e isso e a asercao: nenhum campo
        // do contrato host sobrevive a traducao alem dos tres fatos mais procedencia.
        assertThat(DadosCreditoCore.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactlyInAnyOrder(
                        "limiteChequeEspecialVigente", "situacaoConta",
                        "classificacaoRiscoCreditoBase", "consultadoEm", "fonte");
        assertThat(dados.fonte()).doesNotContain("http");
    }

    @Test
    void dataDeAtualizacaoInvalida_eRespostaInvalida() {
        stubSucesso("000000000500000", "01", "1", "20261332");

        assertThatThrownBy(() -> adapter.consultar(CONTA))
                .isInstanceOf(InvalidCoreLegadoResponseException.class);
    }

    @Test
    void dataDeAtualizacaoForaDoFormatoHost_eRespostaInvalida() {
        stubSucesso("000000000500000", "01", "1", "2026-01-15");

        assertThatThrownBy(() -> adapter.consultar(CONTA))
                .isInstanceOf(InvalidCoreLegadoResponseException.class);
    }

    // --- COD-RET e campos ausentes -----------------------------------------------------------

    @Test
    void contaNaoEncontrada_devolveAusencia_semLancar() {
        wireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(json("""
                        {"codRet":"121","msgRet":"CONTA NAO ENCONTRADA","numCta":"0000010001",
                         "vlrLimChqEsp":"","sitCta":"","codRscCrd":"","datAtuLim":""}
                        """)));

        assertThat(adapter.consultar(CONTA)).isEqualTo(Optional.empty());
    }

    @Test
    void codRetDesconhecido_naoAtravessaAFronteira() {
        wireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(json("""
                        {"codRet":"117","msgRet":"CONTA NAO ELEGIVEL","numCta":"0000010001",
                         "vlrLimChqEsp":"","sitCta":"","codRscCrd":"","datAtuLim":""}
                        """)));

        assertThatThrownBy(() -> adapter.consultar(CONTA))
                .isInstanceOf(InvalidCoreLegadoResponseException.class)
                .hasMessageContaining("117");
    }

    @Test
    void respostaSemCodRet_eRespostaInvalida() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(json("""
                {"msgRet":"?","numCta":"0000010001"}
                """)));

        assertThatThrownBy(() -> adapter.consultar(CONTA))
                .isInstanceOf(InvalidCoreLegadoResponseException.class);
    }

    @Test
    void sucessoComLimiteEmBranco_eRespostaInvalida() {
        stubSucesso("", "01", "1", "20260115");

        assertThatThrownBy(() -> adapter.consultar(CONTA))
                .isInstanceOf(InvalidCoreLegadoResponseException.class);
    }

    @Test
    void sucessoComSituacaoEmBranco_eRespostaInvalida() {
        stubSucesso("000000000500000", "", "1", "20260115");

        assertThatThrownBy(() -> adapter.consultar(CONTA))
                .isInstanceOf(InvalidCoreLegadoResponseException.class);
    }

    @Test
    void limiteNaoNumerico_eRespostaInvalida_naoNumberFormatCru() {
        stubSucesso("ABC", "01", "1", "20260115");

        assertThatThrownBy(() -> adapter.consultar(CONTA))
                .isInstanceOf(InvalidCoreLegadoResponseException.class);
    }

    @Test
    void limiteNegativo_eRespostaInvalida() {
        stubSucesso("-00000000500000", "01", "1", "20260115");

        assertThatThrownBy(() -> adapter.consultar(CONTA))
                .isInstanceOf(InvalidCoreLegadoResponseException.class);
    }

    @Test
    void payloadMalformado_eRespostaInvalida() {
        wireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"codRet\":\"000\",")));

        assertThatThrownBy(() -> adapter.consultar(CONTA))
                .isInstanceOf(InvalidCoreLegadoResponseException.class);
    }

    // --- Patologias de transporte ------------------------------------------------------------

    @Test
    void timeout_viraTimeoutTipado() {
        wireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withFixedDelay(2000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"codRet\":\"121\"}")));

        assertThatThrownBy(() -> adapter.consultar(CONTA))
                .isInstanceOf(CoreLegadoTimeoutException.class);
    }

    @Test
    void erroDeServidorDoHost_viraIndisponibilidade() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> adapter.consultar(CONTA))
                .isInstanceOf(CoreLegadoUnavailableException.class);
    }

    @Test
    void conexaoInterrompida_viraIndisponibilidade() {
        wireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> adapter.consultar(CONTA))
                .isInstanceOf(CoreLegadoUnavailableException.class);
    }

    // --- Helpers -----------------------------------------------------------------------------

    private void stubSucesso(String vlrLimChqEsp, String sitCta, String codRscCrd, String datAtuLim) {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(json("""
                {"codRet":"000","msgRet":"OPERACAO CONCLUIDA COM SUCESSO","numCta":"0000010001",
                 "vlrLimChqEsp":"%s","sitCta":"%s","codRscCrd":"%s","datAtuLim":"%s"}
                """.formatted(vlrLimChqEsp, sitCta, codRscCrd, datAtuLim))));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String corpo) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(corpo);
    }
}
