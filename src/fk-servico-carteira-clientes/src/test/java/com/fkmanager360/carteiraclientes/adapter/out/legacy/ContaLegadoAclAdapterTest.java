package com.fkmanager360.carteiraclientes.adapter.out.legacy;

import com.fkmanager360.carteiraclientes.application.port.out.CoreLegadoTimeoutException;
import com.fkmanager360.carteiraclientes.application.port.out.CoreLegadoUnavailableException;
import com.fkmanager360.carteiraclientes.application.port.out.InvalidCoreLegadoResponseException;
import com.fkmanager360.carteiraclientes.domain.ClienteId;
import com.fkmanager360.carteiraclientes.domain.ContaCorrente;
import com.fkmanager360.carteiraclientes.domain.ContaId;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S4: a fatia de contas da ACL provada contra um mock HTTP server real, com o mesmo RestClient e
 * adapter de producao (ADR-0018). O simulador real e bem comportado; e aqui que a robustez do
 * adapter e de fato provada.
 */
class ContaLegadoAclAdapterTest {

    private static final String PATH = "/legado/contas/consulta";

    private WireMockServer wireMock;
    private ContaLegadoAclAdapter adapter;

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

        adapter = new ContaLegadoAclAdapter(restClient);
    }

    @AfterEach
    void pararMockServer() {
        wireMock.stop();
    }

    @Test
    void contasDoCliente_traduzNumeroDaContaSemZeroPaddingEPreservaAgencia() {
        stub("""
                {"codRet":"000","msgRet":"OPERACAO CONCLUIDA COM SUCESSO","codCli":"0000000001","contas":[
                  {"numCta":"0000010001","codAge":"0001"},
                  {"numCta":"0000010002","codAge":"0001"}
                ]}
                """);

        List<ContaCorrente> contas = adapter.buscarContasDoCliente(new ClienteId("1"));

        assertThat(contas).containsExactly(
                new ContaCorrente(new ContaId("10001"), "0001"),
                new ContaCorrente(new ContaId("10002"), "0001"));
    }

    @Test
    void request_enviaCodCliComZeroPaddingDeDezDigitos() {
        stub("""
                {"codRet":"121","msgRet":"CONTA NAO ENCONTRADA","codCli":"0000000007","contas":[]}
                """);

        adapter.buscarContasDoCliente(new ClienteId("7"));

        wireMock.verify(WireMock.postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(equalToJson("""
                        {"codCli":"0000000007"}
                        """)));
    }

    @Test
    void clienteSemConta_devolveListaVazia_semLancar() {
        stub("""
                {"codRet":"121","msgRet":"CONTA NAO ENCONTRADA","codCli":"0000000009","contas":[]}
                """);

        assertThat(adapter.buscarContasDoCliente(new ClienteId("9"))).isEmpty();
    }

    @Test
    void agenciaEmBranco_eCampoOpcionalAusente_naoErro() {
        stub("""
                {"codRet":"000","msgRet":"OK","codCli":"0000000001","contas":[
                  {"numCta":"0000010001","codAge":""}
                ]}
                """);

        assertThat(adapter.buscarContasDoCliente(new ClienteId("1")))
                .containsExactly(new ContaCorrente(new ContaId("10001"), ""));
    }

    @Test
    void zeroPaddingInesperado_naoCorrompeOIdentificadorInterno() {
        // O host pode devolver mais zeros do que o combinado; o identificador interno continua
        // sendo o mesmo numero.
        stub("""
                {"codRet":"000","msgRet":"OK","codCli":"0000000001","contas":[
                  {"numCta":"0000000042","codAge":"0001"}
                ]}
                """);

        assertThat(adapter.buscarContasDoCliente(new ClienteId("1")))
                .containsExactly(new ContaCorrente(new ContaId("42"), "0001"));
    }

    @Test
    void codRetDesconhecido_naoAtravessaAFronteira_viraRespostaInvalida() {
        stub("""
                {"codRet":"999","msgRet":"ERRO NAO CATALOGADO","codCli":"0000000001","contas":[]}
                """);

        assertThatThrownBy(() -> adapter.buscarContasDoCliente(new ClienteId("1")))
                .isInstanceOf(InvalidCoreLegadoResponseException.class)
                .hasMessageContaining("999");
    }

    @Test
    void sucessoSemListaDeContas_eRespostaInvalida() {
        stub("""
                {"codRet":"000","msgRet":"OK","codCli":"0000000001"}
                """);

        assertThatThrownBy(() -> adapter.buscarContasDoCliente(new ClienteId("1")))
                .isInstanceOf(InvalidCoreLegadoResponseException.class);
    }

    @Test
    void itemSemNumeroDeConta_eRespostaInvalida() {
        stub("""
                {"codRet":"000","msgRet":"OK","codCli":"0000000001","contas":[{"numCta":"","codAge":"0001"}]}
                """);

        assertThatThrownBy(() -> adapter.buscarContasDoCliente(new ClienteId("1")))
                .isInstanceOf(InvalidCoreLegadoResponseException.class);
    }

    @Test
    void numeroDeContaNaoNumerico_eRespostaInvalida_naoIllegalArgumentCru() {
        stub("""
                {"codRet":"000","msgRet":"OK","codCli":"0000000001","contas":[{"numCta":"ABC0010001","codAge":"0001"}]}
                """);

        assertThatThrownBy(() -> adapter.buscarContasDoCliente(new ClienteId("1")))
                .isInstanceOf(InvalidCoreLegadoResponseException.class);
    }

    @Test
    void payloadMalformado_eRespostaInvalida() {
        wireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"codRet\":\"000\",\"contas\":[")));

        assertThatThrownBy(() -> adapter.buscarContasDoCliente(new ClienteId("1")))
                .isInstanceOf(InvalidCoreLegadoResponseException.class);
    }

    @Test
    void timeout_viraTimeoutTipado() {
        wireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withFixedDelay(2000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"codRet\":\"000\",\"contas\":[]}")));

        assertThatThrownBy(() -> adapter.buscarContasDoCliente(new ClienteId("1")))
                .isInstanceOf(CoreLegadoTimeoutException.class);
    }

    @Test
    void erroDeServidorDoHost_viraIndisponibilidade() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> adapter.buscarContasDoCliente(new ClienteId("1")))
                .isInstanceOf(CoreLegadoUnavailableException.class);
    }

    @Test
    void conexaoInterrompida_viraIndisponibilidade() {
        wireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> adapter.buscarContasDoCliente(new ClienteId("1")))
                .isInstanceOf(CoreLegadoUnavailableException.class);
    }

    private void stub(String corpoJson) {
        wireMock.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(corpoJson)));
    }
}
