package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import com.fkmanager360.simuladorcorelegado.domain.ClientesLegadoStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Contrato host-centric ficticio da POC sobre HTTP simples (ADR-0005) -- nao uma reproducao do
 * legado real de nenhum banco. Uma unica capacidade neste ticket: consulta em lote dos dados
 * mestres do Cliente, para que a listagem paginada da carteira nao dispare uma chamada por
 * cliente da pagina.
 *
 * <p>Sem autenticacao, por decisao consciente e nao omissao: o proprio ADR-0005 trata este
 * transporte como simulacao deliberadamente simples, o host-centric real que ele imita nao
 * conhece OAuth2, e o unico consumidor e a ACL de servico-carteira-clientes, alcancado apenas
 * pela rede interna do Compose (nenhuma porta publicada para este servico).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "cliente-legado", description = "Consulta em lote de dados mestres, formato host-centric fictício.")
public class ClienteLegadoController {

    private final ClientesLegadoStore store;

    @Operation(
            operationId = "consultarClientesLegadoEmLote",
            summary = "Consulta em lote de dados mestres do Cliente pelo codigo host",
            description = "codCli e sempre numero de 10 digitos com zero-padding (formato host). O lote "
                    + "respeita uma quantidade maxima de ocorrencias (OCCURS ficticio desta POC). O status "
                    + "HTTP nao carrega o resultado de negocio por item -- um lote pode ter sucessos e "
                    + "\"104 CLIENTE NAO ENCONTRADO\" no mesmo 200; codRet/msgRet no nivel do lote referem-se "
                    + "ao processamento da requisicao como um todo.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Lote processado (pode conter itens com codRet de erro individualmente).",
                    content = @Content(schema = @Schema(implementation = ClientesLegadoQueryResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Requisicao estruturalmente invalida: lista vazia, mais de 50 ocorrencias, "
                            + "ou codCli que nao e uma string de 10 digitos.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    })
    @PostMapping(path = "/legado/clientes/consulta-lote")
    public ClientesLegadoQueryResponse consultarLote(@Valid @RequestBody ClientesLegadoQueryRequest requisicao) {
        List<ClienteLegadoItemResponse> itens = requisicao.codCli().stream()
                .map(codCli -> store.find(codCli)
                        .map(ClienteLegadoItemResponse::sucesso)
                        .orElseGet(() -> ClienteLegadoItemResponse.naoEncontrado(codCli)))
                .toList();
        return ClientesLegadoQueryResponse.processado(itens);
    }
}
