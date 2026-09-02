package com.fkmanager360.carteiraclientes.config;

import com.fkmanager360.carteiraclientes.application.port.out.DadosMestresClientePort;
import com.fkmanager360.carteiraclientes.application.port.out.VinculosCarteiraPort;
import com.fkmanager360.carteiraclientes.domain.ClienteId;
import com.fkmanager360.carteiraclientes.domain.DadosMestresCliente;
import com.fkmanager360.carteiraclientes.domain.GerenteId;
import com.fkmanager360.carteiraclientes.domain.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S6: responsabilidade estrita de routing HTTP, JWT, audience, scope, papel e autorizacao de
 * recurso (ADR-0018) -- nao reexamina regra de dominio, ja provada em S3/S4. Token controlado
 * (ADR-0018): nenhum servidor-autorizacao precisa estar de pe para provar AC21.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "carteira-clientes.core-legado.base-url=http://localhost:0",
                "carteira-clientes.security.expected-audience=" + JwtDecoderTestConfig.EXPECTED_AUDIENCE,
                // S6 nao exercita persistencia (isso e S3): sem esta exclusao, a autoconfiguracao
                // do DataSource tentaria abrir uma conexao real na inicializacao do contexto.
                // Flyway (migracao embutida, ADR-0014) desligado pelo mesmo motivo -- sem
                // datasource real, ele nao teria contra o que migrar.
                "spring.flyway.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration"
        })
@AutoConfigureMockMvc
@Import(JwtDecoderTestConfig.class)
class CarteiraSegurancaTest {

    private static final String AUD = JwtDecoderTestConfig.EXPECTED_AUDIENCE;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VinculosCarteiraPort vinculos;

    @MockitoBean
    private DadosMestresClientePort dadosMestres;

    @BeforeEach
    void comportamentoPadraoDosFakes() {
        when(vinculos.findPage(any(), any()))
                .thenReturn(new PageResult<>(List.of(new ClienteId("1")), 0, 20, 1));
        when(dadosMestres.buscarDadosMestres(any()))
                .thenReturn(Map.of(new ClienteId("1"), new DadosMestresCliente("ANA BEATRIZ SOUZA", "***.222.333-**")));
    }

    @Test
    void semToken_e401() throws Exception {
        mockMvc.perform(get("/carteira/clientes")).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenValido_comAudienceScopeEPapelCorretos_autorizaEDevolveAPagina() throws Exception {
        String token = JwtTestSupport.validToken("gerente.a", AUD, List.of("GERENTE_RELACIONAMENTO"));

        mockMvc.perform(get("/carteira/clientes").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].clienteId").value("1"))
                .andExpect(jsonPath("$.itens[0].nome").value("ANA BEATRIZ SOUZA"))
                .andExpect(jsonPath("$.totalElementos").value(1));
    }

    @Test
    void tokenComAudienceDeOutroResourceServer_eRecusado_401() throws Exception {
        String token = JwtTestSupport.tokenWithWrongAudience("gerente.a");

        mockMvc.perform(get("/carteira/clientes").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenExpirado_e401() throws Exception {
        String token = JwtTestSupport.expiredToken("gerente.a", AUD);

        mockMvc.perform(get("/carteira/clientes").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSemScopeDeCarteiraLeitura_e403() throws Exception {
        String token = JwtTestSupport.tokenWithoutCarteiraScope("gerente.a", AUD);

        mockMvc.perform(get("/carteira/clientes").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void tokenSemPapelGerenteRelacionamento_e403() throws Exception {
        String token = JwtTestSupport.tokenWithoutManagerRole("gerente.a", AUD);

        mockMvc.perform(get("/carteira/clientes").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void gerenteId_derivaDoClaimSub_naoDeParametroAlgum() throws Exception {
        String token = JwtTestSupport.validToken("gerente.b", AUD, List.of("GERENTE_RELACIONAMENTO"));

        mockMvc.perform(get("/carteira/clientes").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(vinculos).findPage(
                org.mockito.ArgumentMatchers.eq(new GerenteId("gerente.b")),
                any());
    }

    @Test
    void healthPermaneceAcessivelSemAutenticacao() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
