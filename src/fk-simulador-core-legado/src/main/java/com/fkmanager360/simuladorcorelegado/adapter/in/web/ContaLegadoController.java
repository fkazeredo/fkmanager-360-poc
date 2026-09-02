package com.fkmanager360.simuladorcorelegado.adapter.in.web;

import com.fkmanager360.simuladorcorelegado.domain.ContasLegadoStore;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * As duas capacidades de conta que este ticket exige, deliberadamente separadas por consumidor
 * (ADR-0004): a consulta de contas de um Cliente e usada pela ACL de CarteiraClientes e nao
 * devolve nenhum dado financeiro; a consulta de credito e usada pela ACL de Credito e devolve
 * apenas os fatos de credito, sem nada cadastral.
 *
 * <p>Sem autenticacao, pela mesma decisao consciente registrada em {@link ClienteLegadoController}.
 */
@RestController
public class ContaLegadoController {

    private final ContasLegadoStore store;

    public ContaLegadoController(ContasLegadoStore store) {
        this.store = store;
    }

    @PostMapping(path = "/legado/contas/consulta")
    public ContasLegadoQueryResponse consultarContasDoCliente(@Valid @RequestBody ContasLegadoQueryRequest requisicao) {
        List<ContaLegadoItemResponse> contas = store.findByCodCli(requisicao.codCli()).stream()
                .map(ContaLegadoItemResponse::de)
                .toList();

        return contas.isEmpty()
                ? ContasLegadoQueryResponse.nenhumaOcorrencia(requisicao.codCli())
                : ContasLegadoQueryResponse.encontradas(requisicao.codCli(), contas);
    }

    @PostMapping(path = "/legado/contas/consulta-credito")
    public CreditoContaLegadoQueryResponse consultarCreditoDaConta(
            @Valid @RequestBody CreditoContaLegadoQueryRequest requisicao) {

        return store.findByNumCta(requisicao.numCta())
                .map(CreditoContaLegadoQueryResponse::de)
                .orElseGet(() -> CreditoContaLegadoQueryResponse.naoEncontrada(requisicao.numCta()));
    }
}
