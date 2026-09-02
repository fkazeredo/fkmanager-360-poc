package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import com.fkmanager360.simuladorcorelegado.domain.ClientesLegadoStore;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Contrato host-centric ficticio da POC sobre HTTP simples (ADR-0005) -- nao uma reproducao do
 * legado real de nenhum banco. Uma unica capacidade neste ticket: consulta em lote dos dados
 * mestres do Cliente, para que a listagem paginada da carteira nao dispare uma chamada por
 * cliente da pagina.
 */
@RestController
public class ClienteLegadoController {

    private final ClientesLegadoStore store;

    public ClienteLegadoController(ClientesLegadoStore store) {
        this.store = store;
    }

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
