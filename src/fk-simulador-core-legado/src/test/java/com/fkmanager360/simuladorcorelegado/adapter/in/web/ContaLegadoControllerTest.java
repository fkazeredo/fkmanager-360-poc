package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import com.fkmanager360.simuladorcorelegado.domain.ContasLegadoStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova o proprio contrato host-centrico das duas capacidades de conta: formato dos campos,
 * "nenhuma ocorrencia" chegando como COD-RET dentro de um HTTP 200 (ADR-0005), e a separacao
 * entre a consulta de contas -- sem nada financeiro -- e a consulta de credito -- sem nada
 * cadastral. As patologias que uma ACL precisa tolerar sao provadas em S4, contra mock HTTP
 * server; aqui o simulador so precisa se comportar corretamente.
 */
@WebMvcTest(ContaLegadoController.class)
@Import(ContasLegadoStore.class)
class ContaLegadoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void consultaDeContas_devolveAsContasDoClienteComIdentificacaoHost() throws Exception {
        mockMvc.perform(post("/legado/contas/consulta")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"codCli": "0000000001"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codRet").value("000"))
                .andExpect(jsonPath("$.codCli").value("0000000001"))
                .andExpect(jsonPath("$.contas.length()").value(2))
                .andExpect(jsonPath("$.contas[0].numCta").value("0000010001"))
                .andExpect(jsonPath("$.contas[0].codAge").value("0001"));
    }

    @Test
    void consultaDeContas_naoDevolveNenhumDadoFinanceiro() throws Exception {
        mockMvc.perform(post("/legado/contas/consulta")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"codCli": "0000000001"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contas[0].vlrLimChqEsp").doesNotExist())
                .andExpect(jsonPath("$.contas[0].sitCta").doesNotExist())
                .andExpect(jsonPath("$.contas[0].codRscCrd").doesNotExist());
    }

    @Test
    void consultaDeContas_clienteSemConta_devolve200ComCodRet121() throws Exception {
        mockMvc.perform(post("/legado/contas/consulta")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"codCli": "9999999999"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codRet").value("121"))
                .andExpect(jsonPath("$.contas.length()").value(0));
    }

    @Test
    void consultaDeContas_codCliForaDoFormatoHost_e400() throws Exception {
        mockMvc.perform(post("/legado/contas/consulta")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"codCli": "1"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void consultaDeContas_semCodCli_e400() throws Exception {
        mockMvc.perform(post("/legado/contas/consulta")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void consultaDeCredito_devolveLimiteSituacaoRiscoEDataDeAtualizacaoDoHost() throws Exception {
        mockMvc.perform(post("/legado/contas/consulta-credito")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"numCta": "0000010001"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codRet").value("000"))
                .andExpect(jsonPath("$.numCta").value("0000010001"))
                // Centavos com zero-padding, sem separador: R$ 5.000,00.
                .andExpect(jsonPath("$.vlrLimChqEsp").value("000000000500000"))
                .andExpect(jsonPath("$.sitCta").value("01"))
                .andExpect(jsonPath("$.codRscCrd").value("1"))
                .andExpect(jsonPath("$.datAtuLim").value("20260115"));
    }

    @Test
    void consultaDeCredito_naoDevolveNenhumDadoCadastralDoCliente() throws Exception {
        mockMvc.perform(post("/legado/contas/consulta-credito")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"numCta": "0000010001"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codCli").doesNotExist())
                .andExpect(jsonPath("$.nomCli").doesNotExist())
                .andExpect(jsonPath("$.numCpf").doesNotExist());
    }

    @Test
    void consultaDeCredito_contaInexistente_devolve200ComCodRet121ECamposEmBranco() throws Exception {
        mockMvc.perform(post("/legado/contas/consulta-credito")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"numCta": "0000099999"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codRet").value("121"))
                .andExpect(jsonPath("$.vlrLimChqEsp").value(""))
                .andExpect(jsonPath("$.sitCta").value(""))
                .andExpect(jsonPath("$.datAtuLim").value(""));
    }

    @Test
    void consultaDeCredito_numCtaForaDoFormatoHost_e400() throws Exception {
        mockMvc.perform(post("/legado/contas/consulta-credito")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"numCta": "10001"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
