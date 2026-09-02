package com.fkmanager360.simuladorcorelegado.cliente;

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
 * Prova o proprio contrato host-centrico do simulador: sucesso, cliente nao encontrado no mesmo
 * lote com HTTP 200 (ADR-0005), e as validacoes estruturais do lote. Os cenarios patologicos que
 * uma ACL precisa tolerar (payload malformado, timeout, indisponibilidade...) sao provados contra
 * um mock HTTP server no S4 de servico-carteira-clientes, nao aqui -- este simulador precisa
 * apenas se comportar corretamente.
 */
@WebMvcTest(ClienteLegadoController.class)
@Import(ClientesLegadoStore.class)
class ClienteLegadoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void lote_comClienteExistente_devolveDadosMestresComCodRetSucesso() throws Exception {
        mockMvc.perform(post("/legado/clientes/consulta-lote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"codCli": ["0000000001"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codRet").value("000"))
                .andExpect(jsonPath("$.clientes[0].codCli").value("0000000001"))
                .andExpect(jsonPath("$.clientes[0].codRet").value("000"))
                .andExpect(jsonPath("$.clientes[0].nomCli").value("ANA BEATRIZ SOUZA"))
                .andExpect(jsonPath("$.clientes[0].sitCad").value("01"));
    }

    @Test
    void lote_comClienteInexistente_devolve200ComCodRet104NoItem() throws Exception {
        mockMvc.perform(post("/legado/clientes/consulta-lote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"codCli": ["9999999999"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientes[0].codRet").value("104"))
                .andExpect(jsonPath("$.clientes[0].nomCli").value(""));
    }

    @Test
    void lote_misto_devolveResultadoIndependentePorItem_noMesmoHttp200() throws Exception {
        mockMvc.perform(post("/legado/clientes/consulta-lote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"codCli": ["0000000001", "9999999999", "0000000101"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientes.length()").value(3))
                .andExpect(jsonPath("$.clientes[0].codRet").value("000"))
                .andExpect(jsonPath("$.clientes[1].codRet").value("104"))
                .andExpect(jsonPath("$.clientes[2].codRet").value("000"));
    }

    @Test
    void lote_vazio_e400() throws Exception {
        mockMvc.perform(post("/legado/clientes/consulta-lote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"codCli": []}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lote_acimaDaQuantidadeMaximaDeOcorrencias_e400() throws Exception {
        String codigos = java.util.stream.IntStream.rangeClosed(1, ClientesLegadoQueryRequest.MAX_OCORRENCIAS + 1)
                .mapToObj(i -> "\"%010d\"".formatted(i))
                .collect(java.util.stream.Collectors.joining(","));

        mockMvc.perform(post("/legado/clientes/consulta-lote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codCli\": [" + codigos + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void codCli_semZeroPaddingDeDezDigitos_e400() throws Exception {
        mockMvc.perform(post("/legado/clientes/consulta-lote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"codCli": ["1"]}
                                """))
                .andExpect(status().isBadRequest());
    }
}
