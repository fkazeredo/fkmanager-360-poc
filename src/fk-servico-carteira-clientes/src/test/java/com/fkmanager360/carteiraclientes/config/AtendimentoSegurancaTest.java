package com.fkmanager360.carteiraclientes.config;

import com.fkmanager360.carteiraclientes.application.port.out.ContasClientePort;
import com.fkmanager360.carteiraclientes.application.port.out.DadosMestresClientePort;
import com.fkmanager360.carteiraclientes.application.port.out.VinculosCarteiraPort;
import com.fkmanager360.carteiraclientes.domain.ClienteId;
import com.fkmanager360.carteiraclientes.domain.ContaCorrente;
import com.fkmanager360.carteiraclientes.domain.ContaId;
import com.fkmanager360.carteiraclientes.domain.DadosMestresCliente;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S6 dos endpoints de atendimento: routing, JWT, audience, scope, papel e autorizacao de
 * recurso. Nao reexamina a orquestracao (S2) nem a traducao da ACL (S4).
 *
 * <p>O AC23 e provado aqui na sua forma mais literal: uma requisicao que chega direto ao
 * Resource Server, sem passar por nenhuma restricao de navegacao do app-gerente, recebe 403 --
 * e as portas de ACL do CoreLegado sao verificadas como <b>nunca invocadas</b>.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "carteira-clientes.core-legado.base-url=http://localhost:0",
                "carteira-clientes.security.expected-audience=" + JwtDecoderTestConfig.EXPECTED_AUDIENCE,
                "spring.flyway.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration"
        })
@AutoConfigureMockMvc
@Import(JwtDecoderTestConfig.class)
class AtendimentoSegurancaTest {

    private static final String AUD = JwtDecoderTestConfig.EXPECTED_AUDIENCE;
    private static final String CONTAS = "/clientes/1/contas";
    private static final String CONTEXTO = "/clientes/1/contas/10001/contexto-atendimento";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VinculosCarteiraPort vinculos;

    @MockitoBean
    private ContasClientePort contas;

    @MockitoBean
    private DadosMestresClientePort dadosMestres;

    @BeforeEach
    void comportamentoPadraoDosFakes() {
        when(vinculos.existeVinculo(any(), any())).thenReturn(true);
        when(contas.buscarContasDoCliente(any()))
                .thenReturn(List.of(new ContaCorrente(new ContaId("10001"), "0001")));
        when(dadosMestres.buscarDadosMestres(any()))
                .thenReturn(Map.of(new ClienteId("1"),
                        new DadosMestresCliente("ANA BEATRIZ SOUZA", "***.222.333-**")));
    }

    private String tokenDeGerente(String sub) {
        return JwtTestSupport.validToken(sub, AUD, List.of("GERENTE_RELACIONAMENTO"));
    }

    // --- Autenticacao e capacidades ---------------------------------------------------------

    @Test
    void contas_semToken_e401() throws Exception {
        mockMvc.perform(get(CONTAS)).andExpect(status().isUnauthorized());
    }

    @Test
    void contextoAtendimento_semToken_e401() throws Exception {
        mockMvc.perform(get(CONTEXTO)).andExpect(status().isUnauthorized());
    }

    @Test
    void contextoAtendimento_tokenDeOutroResourceServer_eRecusado_401() throws Exception {
        mockMvc.perform(get(CONTEXTO)
                        .header("Authorization", "Bearer " + JwtTestSupport.tokenWithWrongAudience("gerente.a")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void contextoAtendimento_semScopeDeCarteiraLeitura_e403() throws Exception {
        mockMvc.perform(get(CONTEXTO)
                        .header("Authorization", "Bearer " + JwtTestSupport.tokenWithoutCarteiraScope("gerente.a", AUD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void contextoAtendimento_semPapelGerenteRelacionamento_e403() throws Exception {
        mockMvc.perform(get(CONTEXTO)
                        .header("Authorization", "Bearer " + JwtTestSupport.tokenWithoutManagerRole("gerente.a", AUD)))
                .andExpect(status().isForbidden());
    }

    // --- AC22: selecionar um Cliente devolve suas ContaCorrentes -----------------------------

    @Test
    void contas_comDireitoAtual_devolveAsContasDoCliente() throws Exception {
        mockMvc.perform(get(CONTAS).header("Authorization", "Bearer " + tokenDeGerente("gerente.a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens.length()").value(1))
                .andExpect(jsonPath("$.itens[0].contaId").value("10001"))
                .andExpect(jsonPath("$.itens[0].agencia").value("0001"));
    }

    @Test
    void contas_naoExpoemNenhumDadoFinanceiro() throws Exception {
        // AC30: servico-carteira-clientes nao expoe nem conhece LimiteChequeEspecial.
        mockMvc.perform(get(CONTAS).header("Authorization", "Bearer " + tokenDeGerente("gerente.a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].limiteChequeEspecialVigente").doesNotExist())
                .andExpect(jsonPath("$.itens[0].saldo").doesNotExist())
                .andExpect(jsonPath("$.itens[0].situacaoConta").doesNotExist());
    }

    // --- AC23: sem direito, 403 e nenhuma chamada ao CoreLegado -----------------------------

    @Test
    void contas_semDireitoDeAtendimentoAtual_e403_eNenhumaChamadaAoCoreLegado() throws Exception {
        when(vinculos.existeVinculo(any(), any())).thenReturn(false);

        mockMvc.perform(get(CONTAS).header("Authorization", "Bearer " + tokenDeGerente("gerente.a")))
                .andExpect(status().isForbidden());

        verify(contas, never()).buscarContasDoCliente(any());
        verify(dadosMestres, never()).buscarDadosMestres(any());
    }

    @Test
    void contextoAtendimento_semDireitoDeAtendimentoAtual_e403_eNenhumaChamadaAoCoreLegado() throws Exception {
        when(vinculos.existeVinculo(any(), any())).thenReturn(false);

        mockMvc.perform(get(CONTEXTO).header("Authorization", "Bearer " + tokenDeGerente("gerente.a")))
                .andExpect(status().isForbidden());

        verify(contas, never()).buscarContasDoCliente(any());
        verify(dadosMestres, never()).buscarDadosMestres(any());
    }

    @Test
    void contextoAtendimento_gerenteId_derivaDoClaimSub_naoDoCaminho() throws Exception {
        when(vinculos.existeVinculo(new com.fkmanager360.carteiraclientes.domain.GerenteId("gerente.b"), new ClienteId("1")))
                .thenReturn(false);

        // O mesmo caminho, com um gerente diferente no token, e recusado: a identidade vem do
        // token e nunca do que o chamador escreveu na URL.
        mockMvc.perform(get(CONTEXTO).header("Authorization", "Bearer " + tokenDeGerente("gerente.b")))
                .andExpect(status().isForbidden());
    }

    // --- Contexto de atendimento ------------------------------------------------------------

    @Test
    void contextoAtendimento_comDireitoAtual_devolveClienteEConta() throws Exception {
        mockMvc.perform(get(CONTEXTO).header("Authorization", "Bearer " + tokenDeGerente("gerente.a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clienteId").value("1"))
                .andExpect(jsonPath("$.nome").value("ANA BEATRIZ SOUZA"))
                .andExpect(jsonPath("$.cpfMascarado").value("***.222.333-**"))
                .andExpect(jsonPath("$.conta.contaId").value("10001"))
                .andExpect(jsonPath("$.conta.agencia").value("0001"));
    }

    @Test
    void contextoAtendimento_contaQueNaoEDoCliente_e404() throws Exception {
        mockMvc.perform(get("/clientes/1/contas/99999/contexto-atendimento")
                        .header("Authorization", "Bearer " + tokenDeGerente("gerente.a")))
                .andExpect(status().isNotFound());
    }

    @Test
    void contextoAtendimento_naoExpoeLimiteChequeEspecial() throws Exception {
        mockMvc.perform(get(CONTEXTO).header("Authorization", "Bearer " + tokenDeGerente("gerente.a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limiteChequeEspecialVigente").doesNotExist())
                .andExpect(jsonPath("$.conta.limiteChequeEspecialVigente").doesNotExist());
    }
}
