package com.fkmanager360.carteiraclientes.adapter.in.web;

import com.fkmanager360.carteiraclientes.application.usecase.ListarClientesDaCarteira;
import com.fkmanager360.carteiraclientes.domain.GerenteId;
import com.fkmanager360.carteiraclientes.domain.Pagination;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AC22: o gerente autenticado ve somente os Clientes da sua CarteiraClientes, paginada. O
 * gerenteId vem do claim {@code sub} do token ja validado pela borda -- este controller nao
 * reimplementa autenticacao, so traduz identidade autenticada em conceito de aplicacao
 * (ADR-0007).
 */
@RestController
public class CarteiraController {

    private final ListarClientesDaCarteira listarClientesDaCarteira;

    public CarteiraController(ListarClientesDaCarteira listarClientesDaCarteira) {
        this.listarClientesDaCarteira = listarClientesDaCarteira;
    }

    @GetMapping("/carteira/clientes")
    ClientesPageResponse listar(
            @AuthenticationPrincipal Jwt jwt,
            // name= explicito: o parametro de query publico ("pagina"/"tamanho") e contrato HTTP
            // ja exercitado por Angular/Playwright/curl -- so o identificador Java interno segue
            // a convencao tecnica em ingles.
            @RequestParam(name = "pagina", defaultValue = "0") int page,
            @RequestParam(name = "tamanho", defaultValue = "" + Pagination.DEFAULT_SIZE) int size) {

        GerenteId gerenteId = new GerenteId(jwt.getSubject());
        var resultado = listarClientesDaCarteira.executar(gerenteId, new Pagination(page, size));
        return ClientesPageResponse.de(resultado);
    }
}
