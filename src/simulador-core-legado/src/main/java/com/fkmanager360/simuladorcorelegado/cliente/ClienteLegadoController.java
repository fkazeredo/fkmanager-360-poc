package com.fkmanager360.simuladorcorelegado.cliente;

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

    private final BaseClientesLegado base;

    public ClienteLegadoController(BaseClientesLegado base) {
        this.base = base;
    }

    @PostMapping(path = "/legado/clientes/consulta-lote")
    public ConsultaClientesLegadoResposta consultarLote(@Valid @RequestBody ConsultaClientesLegadoRequisicao requisicao) {
        List<ClienteLegadoItemResposta> itens = requisicao.codCli().stream()
                .map(codCli -> base.buscar(codCli)
                        .map(ClienteLegadoItemResposta::sucesso)
                        .orElseGet(() -> ClienteLegadoItemResposta.naoEncontrado(codCli)))
                .toList();
        return ConsultaClientesLegadoResposta.processado(itens);
    }
}
