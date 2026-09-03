package com.fkmanager360.bffgerente.adapter.in.web;

import com.fkmanager360.bffgerente.config.DelegatedTokenResolver;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S6 da composicao (AC30): o modelo de apresentacao da tela de atendimento e montado pelo
 * bff-gerente a partir de servico-carteira-clientes e servico-credito.
 *
 * <p>A ausencia de chamada ao simulador-core-legado NAO e provada aqui com um WireMock cujo
 * endereco nenhum componente conhece -- isso seria verde por construcao, nao falsificavel
 * (achado I7 do review de #0002). A prova estrutural esta em
 * {@link com.fkmanager360.bffgerente.config.TopologiaDeDependenciasTest}: nenhuma classe do
 * simulador no classpath do BFF, e o unico conjunto de {@code RestClient} configurados sao os
 * dois destinos autorizados.
 *
 * <p>O Token Exchange em si ja e provado por {@code BffSegurancaTest} e pelo S6 do
 * servidor-autorizacao; aqui ele e substituido por um resolver stubado para que o objeto do teste
 * seja a composicao, e nao a obtencao de token. O que se verifica sobre delegacao e o essencial
 * para a composicao estar correta: <b>cada destino recebe o token emitido para ele</b>.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                // O objeto deste teste e a composicao, nao a sessao: sem estas exclusoes, uma
                // requisicao anonima tentaria abrir sessao em Redis de verdade. Sessao, cookie e
                // CSRF sao provados em BffSegurancaTest, contra um Redis real.
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
class ComposicaoAtendimentoTest {

    private static final String PATH_CONTEXTO = "/clientes/1/contas/10001/contexto-atendimento";
    private static final String PATH_LIMITE = "/clientes/1/contas/10001/limite-cheque-especial-vigente";
    private static final String ENDPOINT = "/api/clientes/1/contas/10001/atendimento";

    private static final String TOKEN_CARTEIRA = "token-para-servico-carteira-clientes";
    private static final String TOKEN_CREDITO = "token-para-servico-credito";

    private static WireMockServer carteiraClientes;
    private static WireMockServer credito;

    @BeforeAll
    static void subirServicos() {
        carteiraClientes = novoServidor();
        credito = novoServidor();
    }

    @AfterAll
    static void pararServicos() {
        carteiraClientes.stop();
        credito.stop();
    }

    private static WireMockServer novoServidor() {
        WireMockServer servidor = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        servidor.start();
        return servidor;
    }

    @DynamicPropertySource
    static void apontarDependencias(DynamicPropertyRegistry registry) {
        registry.add("bff-gerente.carteira-clientes.base-url", () -> "http://localhost:" + carteiraClientes.port());
        registry.add("bff-gerente.credito.base-url", () -> "http://localhost:" + credito.port());
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DelegatedTokenResolver tokenResolver;

    @BeforeEach
    void comportamentoPadrao() {
        carteiraClientes.resetAll();
        credito.resetAll();

        when(tokenResolver.tokenPara(eq("carteira-clientes-exchange"), any(), any(), any()))
                .thenReturn(TOKEN_CARTEIRA);
        when(tokenResolver.tokenPara(eq("credito-leitura-exchange"), any(), any(), any()))
                .thenReturn(TOKEN_CREDITO);

        carteiraClientes.stubFor(WireMock.get(urlEqualTo(PATH_CONTEXTO)).willReturn(json("""
                {"clienteId":"1","nome":"ANA BEATRIZ SOUZA","cpfMascarado":"***.222.333-**",
                 "conta":{"contaId":"10001","agencia":"0001"}}
                """)));

        credito.stubFor(WireMock.get(urlEqualTo(PATH_LIMITE)).willReturn(json("""
                {"contaId":"10001","limiteChequeEspecialVigente":500000,
                 "consultadoEm":"2026-09-02T16:00:00Z"}
                """)));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor gerenteAutenticado() {
        return SecurityMockMvcRequestPostProcessors.oidcLogin().idToken(token -> token.subject("gerente.a"));
    }

    @Test
    void atendimento_montaOModeloDeApresentacaoAPartirDosDoisContextos() throws Exception {
        mockMvc.perform(get(ENDPOINT).with(gerenteAutenticado()))
                .andExpect(status().isOk())
                // De servico-carteira-clientes: identidade e vinculo.
                .andExpect(jsonPath("$.cliente.clienteId").value("1"))
                .andExpect(jsonPath("$.cliente.nome").value("ANA BEATRIZ SOUZA"))
                .andExpect(jsonPath("$.cliente.cpfMascarado").value("***.222.333-**"))
                .andExpect(jsonPath("$.conta.contaId").value("10001"))
                .andExpect(jsonPath("$.conta.agencia").value("0001"))
                // De servico-credito: o limite que o Core reconhece agora.
                .andExpect(jsonPath("$.limiteChequeEspecialVigente").value(500000))
                .andExpect(jsonPath("$.consultadoEm").exists());
    }

    @Test
    void atendimento_apresentaACadaDestinoOTokenEmitidoParaEle() throws Exception {
        mockMvc.perform(get(ENDPOINT).with(gerenteAutenticado())).andExpect(status().isOk());

        // Nao existe token de usuario multi-audience circulando pela plataforma (ADR-0015).
        carteiraClientes.verify(getRequestedFor(urlEqualTo(PATH_CONTEXTO))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + TOKEN_CARTEIRA)));
        credito.verify(getRequestedFor(urlEqualTo(PATH_LIMITE))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + TOKEN_CREDITO)));
    }

    /**
     * 403/404 SEMPRE atravessam com o mesmo status, independentemente de o upstream publicar
     * {@code codigo} (AC23, provado desde #0002 -- este teste continua afirmando exatamente essa
     * garantia). {@code fk-servico-carteira-clientes} nao foi tocado por #0003 e nao publica
     * {@code codigo}; por isso o envelope sai sem essa propriedade, mas o STATUS nunca regride
     * para 502 -- 403/404 sao resposta autoritativa do backend dono do recurso (ADR-0007), nao
     * falha de integracao (ver Javadoc de {@link GlobalExceptionHandler}).
     */
    @Test
    void atendimento_semDireitoDeAtendimento_atravessaO403SemReinterpretar() throws Exception {
        carteiraClientes.stubFor(WireMock.get(urlEqualTo(PATH_CONTEXTO)).willReturn(aResponse().withStatus(403)));

        mockMvc.perform(get(ENDPOINT).with(gerenteAutenticado()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").doesNotExist());

        // O BFF nao e enforcement point unico, mas tambem nao suaviza a recusa de quem e dono do
        // recurso: sem contexto de atendimento, nem chega a perguntar o limite.
        credito.verify(0, getRequestedFor(urlEqualTo(PATH_LIMITE)));
    }

    /** Ver Javadoc de {@link #atendimento_semDireitoDeAtendimento_atravessaO403SemReinterpretar}. */
    @Test
    void atendimento_contaInexistente_atravessaO404() throws Exception {
        carteiraClientes.stubFor(WireMock.get(urlEqualTo(PATH_CONTEXTO)).willReturn(aResponse().withStatus(404)));

        mockMvc.perform(get(ENDPOINT).with(gerenteAutenticado()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").doesNotExist());
    }

    /**
     * Ate #0002, a mensagem unica de indisponibilidade era um texto livre em {@code detail}. O
     * envelope publico proprio do plano #0003 (secao 9 "bff-gerente") carrega so
     * {@code status}+{@code codigo} -- nenhum texto de interface vem do backend; a mensagem exibida
     * ao gerente pertence ao app-gerente, decidida a partir do {@code codigo}.
     */
    @Test
    void atendimento_creditoIndisponivel_vira503ComCodigoDependenciaIndisponivel() throws Exception {
        credito.stubFor(WireMock.get(urlEqualTo(PATH_LIMITE)).willReturn(aResponse().withStatus(503)));

        mockMvc.perform(get(ENDPOINT).with(gerenteAutenticado()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("DEPENDENCIA_INDISPONIVEL"))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    void listagemDeContas_encaminhaComOTokenDeCarteira() throws Exception {
        carteiraClientes.stubFor(WireMock.get(urlEqualTo("/clientes/1/contas")).willReturn(json("""
                {"itens":[{"contaId":"10001","agencia":"0001"},{"contaId":"10002","agencia":"0001"}]}
                """)));

        mockMvc.perform(get("/api/clientes/1/contas").with(gerenteAutenticado()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens.length()").value(2))
                .andExpect(jsonPath("$.itens[0].contaId").value("10001"));

        carteiraClientes.verify(getRequestedFor(urlEqualTo("/clientes/1/contas"))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + TOKEN_CARTEIRA)));
    }

    @Test
    void semSessaoAutenticada_e401_semChamarNenhumServico() throws Exception {
        mockMvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized());

        carteiraClientes.verify(0, anyRequestedFor(anyUrl()));
        credito.verify(0, anyRequestedFor(anyUrl()));
    }

    // --- I1: taxonomia de erro completa -------------------------------------------------------

    @Test
    void contaIdForaDoFormatoHost_e400_semChamarNenhumServico() throws Exception {
        // Entrada invalida do proprio BFF: recusada na borda, antes de qualquer chamada remota
        // que so falharia la na frente.
        mockMvc.perform(get("/api/clientes/1/contas/abc/atendimento").with(gerenteAutenticado()))
                .andExpect(status().isBadRequest());

        carteiraClientes.verify(0, anyRequestedFor(anyUrl()));
        credito.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void clienteIdForaDoFormatoHost_e400() throws Exception {
        mockMvc.perform(get("/api/clientes/abc/contas").with(gerenteAutenticado()))
                .andExpect(status().isBadRequest());
    }

    /**
     * Um Resource Server recusando o token DELEGADO (401) nao pode virar 401 para o browser: a
     * sessao do BFF continua valida, e isso confundiria "usuario precisa logar de novo" com "a
     * cadeia de Token Exchange quebrou". Vira 502 -- taxonomia de integracao.
     */
    @Test
    void tokenDelegadoRecusadoPeloResourceServer_vira502_naoReautenticaOUsuario() throws Exception {
        carteiraClientes.stubFor(WireMock.get(urlEqualTo(PATH_CONTEXTO)).willReturn(aResponse().withStatus(401)));

        mockMvc.perform(get(ENDPOINT).with(gerenteAutenticado()))
                .andExpect(status().isBadGateway());
    }

    @Test
    void respostaDownstreamInesperada_e502_naoErroGenerico() throws Exception {
        // Um 400 que o backend dono do recurso devolveu por um motivo que a validacao de borda do
        // BFF nao antecipou: falha de integracao, nao erro do usuario, e nao pode virar 500.
        carteiraClientes.stubFor(WireMock.get(urlEqualTo(PATH_CONTEXTO)).willReturn(aResponse().withStatus(400)));

        mockMvc.perform(get(ENDPOINT).with(gerenteAutenticado()))
                .andExpect(status().isBadGateway());
    }

    @Test
    void corpo2xxIncompletoDaCarteira_e502_naoNullPointerNemLimiteZero() throws Exception {
        carteiraClientes.stubFor(WireMock.get(urlEqualTo(PATH_CONTEXTO)).willReturn(json("""
                {"clienteId":"1","nome":"ANA BEATRIZ SOUZA","cpfMascarado":"***.222.333-**"}
                """)));

        mockMvc.perform(get(ENDPOINT).with(gerenteAutenticado()))
                .andExpect(status().isBadGateway());
    }

    @Test
    void corpo2xxComLimiteAusenteDoCredito_e502_naoVira0() throws Exception {
        credito.stubFor(WireMock.get(urlEqualTo(PATH_LIMITE)).willReturn(json("""
                {"contaId":"10001","consultadoEm":"2026-09-02T16:00:00Z"}
                """)));

        mockMvc.perform(get(ENDPOINT).with(gerenteAutenticado()))
                .andExpect(status().isBadGateway());
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String corpo) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(corpo);
    }
}
