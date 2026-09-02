package com.fkmanager360.carteiraclientes.adapters.entrada.rest;

import com.fkmanager360.carteiraclientes.aplicacao.ListarClientesDaCarteira;
import com.fkmanager360.carteiraclientes.dominio.GerenteId;
import com.fkmanager360.carteiraclientes.dominio.Paginacao;
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
    PaginaClientesResponse listar(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "" + Paginacao.TAMANHO_PADRAO) int tamanho) {

        GerenteId gerenteId = new GerenteId(jwt.getSubject());
        var resultado = listarClientesDaCarteira.executar(gerenteId, new Paginacao(pagina, tamanho));
        return PaginaClientesResponse.de(resultado);
    }
}
