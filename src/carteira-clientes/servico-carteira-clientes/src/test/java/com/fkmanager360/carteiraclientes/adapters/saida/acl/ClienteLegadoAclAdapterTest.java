package com.fkmanager360.carteiraclientes.adapters.saida.acl;

import com.fkmanager360.carteiraclientes.aplicacao.portas.CoreLegadoIndisponivelException;
import com.fkmanager360.carteiraclientes.aplicacao.portas.CoreLegadoTimeoutException;
import com.fkmanager360.carteiraclientes.aplicacao.portas.RespostaCoreLegadoInvalidaException;
import com.fkmanager360.carteiraclientes.dominio.ClienteId;
import com.fkmanager360.carteiraclientes.dominio.DadosMestresCliente;
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
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S4: a ACL provada contra um mock HTTP server real, com o mesmo RestClient e adapter de
 * producao (ADR-0018). As patologias abaixo sao sinteticas -- o simulador real (F2) e bem
 * comportado; aqui e onde a robustez do adapter e de fato provada.
 */
class ClienteLegadoAclAdapterTest {

    private WireMockServer wireMock;
    private ClienteLegadoAclAdapter adapter;

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

        adapter = new ClienteLegadoAclAdapter(restClient);
    }

    @AfterEach
    void pararMockServer() {
        wireMock.stop();
    }

    @Test
    void clienteComSucesso_traduzNomeECpfMascarado() {
        wireMock.stubFor(post(urlEqualTo("/legado/clientes/consulta-lote"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"codRet":"000","msgRet":"LOTE PROCESSADO","clientes":[
                                  {"codCli":"0000000001","codRet":"000","msgRet":"OK",
                                   "nomCli":"ANA BEATRIZ SOUZA","numCpf":"11122233396","sitCad":"01","datCad":"20180312"}
                                ]}
                                """)));

        Map<ClienteId, DadosMestresCliente> resultado = adapter.buscarDadosMestres(List.of(new ClienteId("1")));

        assertThat(resultado).containsEntry(new ClienteId("1"),
                new DadosMestresCliente("ANA BEATRIZ SOUZA", "***.222.333-**"));
    }

    @Test
    void request_enviaCodCliComZeroPaddingDeDezDigitos() {
        wireMock.stubFor(post(urlEqualTo("/legado/clientes/consulta-lote"))
                .withRequestBody(equalToJson("""
                        {"codCli":["0000000042"]}
                        """))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"codRet":"000","msgRet":"OK","clientes":[]}
                                """)));

        adapter.buscarDadosMestres(List.of(new ClienteId("42")));

        wireMock.verify(1, WireMock.postRequestedFor(urlEqualTo("/legado/clientes/consulta-lote")));
    }

    @Test
    void clienteNaoEncontrado_ficaAusenteDoMapa_semErro() {
        wireMock.stubFor(post(urlEqualTo("/legado/clientes/consulta-lote"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"codRet":"000","msgRet":"OK","clientes":[
                                  {"codCli":"9999999999","codRet":"104","msgRet":"CLIENTE NAO ENCONTRADO",
                                   "nomCli":"","numCpf":"","sitCad":"","datCad":""}
                                ]}
                                """)));

        Map<ClienteId, DadosMestresCliente> resultado = adapter.buscarDadosMestres(List.of(new ClienteId("9999999999")));

        assertThat(resultado).isEmpty();
    }

    @Test
    void campoNomeEmBranco_eRepresentacaoHostValida_naoErro() {
        wireMock.stubFor(post(urlEqualTo("/legado/clientes/consulta-lote"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"codRet":"000","msgRet":"OK","clientes":[
                                  {"codCli":"0000000001","codRet":"000","msgRet":"OK",
                                   "nomCli":"","numCpf":"11122233396","sitCad":"01","datCad":"20180312"}
                                ]}
                                """)));

        Map<ClienteId, DadosMestresCliente> resultado = adapter.buscarDadosMestres(List.of(new ClienteId("1")));

        assertThat(resultado.get(new ClienteId("1")).nome()).isEmpty();
    }

    @Test
    void cpfComZeroPaddingInesperado_naoQuebra_ficaNaoInformado() {
        wireMock.stubFor(post(urlEqualTo("/legado/clientes/consulta-lote"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"codRet":"000","msgRet":"OK","clientes":[
                                  {"codCli":"0000000001","codRet":"000","msgRet":"OK",
                                   "nomCli":"ANA BEATRIZ SOUZA","numCpf":"123","sitCad":"01","datCad":"20180312"}
                                ]}
                                """)));

        Map<ClienteId, DadosMestresCliente> resultado = adapter.buscarDadosMestres(List.of(new ClienteId("1")));

        assertThat(resultado.get(new ClienteId("1")).cpfMascarado()).isEmpty();
    }

    @Test
    void dataDeCadastroInvalida_naoAfetaNada_poisNaoEUsadaNesteSlice() {
        wireMock.stubFor(post(urlEqualTo("/legado/clientes/consulta-lote"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"codRet":"000","msgRet":"OK","clientes":[
                                  {"codCli":"0000000001","codRet":"000","msgRet":"OK",
                                   "nomCli":"ANA BEATRIZ SOUZA","numCpf":"11122233396","sitCad":"01","datCad":"99999999"}
                                ]}
                                """)));

        Map<ClienteId, DadosMestresCliente> resultado = adapter.buscarDadosMestres(List.of(new ClienteId("1")));

        assertThat(resultado.get(new ClienteId("1")).nome()).isEqualTo("ANA BEATRIZ SOUZA");
    }

    @Test
    void codRetDesconhecido_naoAtravessaParaFora_viraRespostaInvalida() {
        wireMock.stubFor(post(urlEqualTo("/legado/clientes/consulta-lote"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"codRet":"000","msgRet":"OK","clientes":[
                                  {"codCli":"0000000001","codRet":"999","msgRet":"?","nomCli":"","numCpf":"","sitCad":"","datCad":""}
                                ]}
                                """)));

        assertThatThrownBy(() -> adapter.buscarDadosMestres(List.of(new ClienteId("1"))))
                .isInstanceOf(RespostaCoreLegadoInvalidaException.class);
    }

    @Test
    void respostaMalformada_viraRespostaInvalida() {
        wireMock.stubFor(post(urlEqualTo("/legado/clientes/consulta-lote"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{ isto nao e json valido")));

        assertThatThrownBy(() -> adapter.buscarDadosMestres(List.of(new ClienteId("1"))))
                .isInstanceOf(RespostaCoreLegadoInvalidaException.class);
    }

    @Test
    void indisponibilidade_5xx_viraCoreLegadoIndisponivel() {
        wireMock.stubFor(post(urlEqualTo("/legado/clientes/consulta-lote"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> adapter.buscarDadosMestres(List.of(new ClienteId("1"))))
                .isInstanceOf(CoreLegadoIndisponivelException.class);
    }

    @Test
    void timeout_viraCoreLegadoTimeout() {
        wireMock.stubFor(post(urlEqualTo("/legado/clientes/consulta-lote"))
                .willReturn(aResponse().withFixedDelay(2000).withStatus(200)));

        assertThatThrownBy(() -> adapter.buscarDadosMestres(List.of(new ClienteId("1"))))
                .isInstanceOf(CoreLegadoTimeoutException.class);
    }

    @Test
    void connectionReset_viraCoreLegadoIndisponivel() {
        wireMock.stubFor(post(urlEqualTo("/legado/clientes/consulta-lote"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> adapter.buscarDadosMestres(List.of(new ClienteId("1"))))
                .isInstanceOf(CoreLegadoIndisponivelException.class);
    }

    @Test
    void loteVazio_naoChamaORemoto() {
        Map<ClienteId, DadosMestresCliente> resultado = adapter.buscarDadosMestres(List.of());

        assertThat(resultado).isEmpty();
        wireMock.verify(0, WireMock.postRequestedFor(urlEqualTo("/legado/clientes/consulta-lote")));
    }
}
