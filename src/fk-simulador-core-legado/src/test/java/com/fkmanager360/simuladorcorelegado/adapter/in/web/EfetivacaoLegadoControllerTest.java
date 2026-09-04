package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import com.fkmanager360.simuladorcorelegado.domain.ContasLegadoStore;
import com.fkmanager360.simuladorcorelegado.domain.EfetivacoesLegadoStore;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova o proprio contrato de {@code /legado/efetivacoes} (plano #0004, secao 7): deduplicacao
 * funcional por {@code idEft}, payload incompativel e as quatro classes de falha definitiva. O
 * control plane (cenarios de transporte) e provado em S5 contra o container real, ja que so faz
 * sentido ativo num profile (ADR-0018).
 */
@WebMvcTest(EfetivacaoLegadoController.class)
@Import({ContasLegadoStore.class, EfetivacoesLegadoStore.class})
class EfetivacaoLegadoControllerTest {

    private static final String PATH = "/legado/efetivacoes";
    /** Conta 10001: vigente 500000 centavos, sitCta REGULAR (seed de ContasLegadoStore). */
    private static final String NUM_CTA_REGULAR = "0000010001";
    /** Conta 10005: sitCta "02" (bloqueada/irregular). */
    private static final String NUM_CTA_BLOQUEADA = "0000010005";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aceite_novaEfetivacao_devolveNumPrt() throws Exception {
        mockMvc.perform(efetivar("id-eft-1", NUM_CTA_REGULAR, "000000000500000", "000000000600000", "id-cor-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codRet").value("000"))
                .andExpect(jsonPath("$.numPrt").exists())
                .andExpect(jsonPath("$.idCor").value("id-cor-1"));
    }

    @Test
    void mesmoIdEft_mesmoPayload_devolveOMesmoNumPrt() throws Exception {
        String primeira = mockMvc.perform(efetivar("id-eft-2", NUM_CTA_REGULAR, "000000000500000", "000000000600000", "id-cor-2"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String numPrt1 = JsonPath.read(primeira, "$.numPrt").toString();

        mockMvc.perform(efetivar("id-eft-2", NUM_CTA_REGULAR, "000000000500000", "000000000600000", "id-cor-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codRet").value("000"))
                .andExpect(jsonPath("$.numPrt").value(numPrt1));
    }

    @Test
    void mesmoIdEft_payloadDiferente_devolve207_naoTrataComoOperacaoNova() throws Exception {
        mockMvc.perform(efetivar("id-eft-3", NUM_CTA_REGULAR, "000000000500000", "000000000600000", "id-cor-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codRet").value("000"));

        mockMvc.perform(efetivar("id-eft-3", NUM_CTA_REGULAR, "000000000500000", "000000000700000", "id-cor-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codRet").value("207"))
                .andExpect(jsonPath("$.numPrt").doesNotExist());
    }

    @Test
    void contaNaoEncontrada_devolve121() throws Exception {
        mockMvc.perform(efetivar("id-eft-4", "0000099999", "000000000500000", "000000000600000", "id-cor-4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codRet").value("121"));
    }

    @Test
    void contaBloqueada_devolve118() throws Exception {
        mockMvc.perform(efetivar("id-eft-5", NUM_CTA_BLOQUEADA, "000000000300000", "000000000400000", "id-cor-5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codRet").value("118"));
    }

    @Test
    void limiteVigenteDivergente_devolve205() throws Exception {
        mockMvc.perform(efetivar("id-eft-6", NUM_CTA_REGULAR, "000000000999999", "000000001000000", "id-cor-6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codRet").value("205"));
    }

    @Test
    void instrucaoInvalida_vlrLimNovNaoMaiorQueVigente_devolve199() throws Exception {
        mockMvc.perform(efetivar("id-eft-7", NUM_CTA_REGULAR, "000000000500000", "000000000500000", "id-cor-7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codRet").value("199"));
    }

    @Test
    void numCtaForaDoFormatoHost_e400() throws Exception {
        mockMvc.perform(efetivar("id-eft-8", "10001", "000000000500000", "000000000600000", "id-cor-8"))
                .andExpect(status().isBadRequest());
    }

    private static MockHttpServletRequestBuilder efetivar(
            String idEft, String numCta, String vlrLimChqEspEsp, String vlrLimNov, String idCor) {
        return post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"idEft":"%s","numCta":"%s","vlrLimChqEspEsp":"%s","vlrLimNov":"%s","idCor":"%s"}
                        """.formatted(idEft, numCta, vlrLimChqEspEsp, vlrLimNov, idCor));
    }
}
