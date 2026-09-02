package com.fkmanager360.carteiraclientes.adapter.in.web;

import com.fkmanager360.carteiraclientes.application.usecase.ConsultarContextoAtendimento;
import com.fkmanager360.carteiraclientes.application.usecase.ListarContasDoCliente;
import com.fkmanager360.carteiraclientes.domain.ClienteId;
import com.fkmanager360.carteiraclientes.domain.ContaId;
import com.fkmanager360.carteiraclientes.domain.GerenteId;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * AC22 e AC23: selecionar um Cliente devolve suas ContaCorrentes, e a autorizacao de recurso e
 * produzida aqui -- pelo backend dono da associacao --, nunca pelo app-gerente ou pelo
 * bff-gerente (ADR-0007).
 *
 * <p>O {@code gerenteId} vem do claim {@code sub} do token ja validado na borda e nunca de
 * parametro algum; o {@code clienteId} vem do caminho, e existe justamente para que a
 * verificacao de direito possa acontecer <b>antes</b> de qualquer chamada ao CoreLegado. Ele nao
 * e tratado como verdade sobre a quem a conta pertence -- isso quem afirma e o Core.
 */
@RestController
public class AtendimentoController {

    private final ListarContasDoCliente listarContasDoCliente;
    private final ConsultarContextoAtendimento consultarContextoAtendimento;

    public AtendimentoController(
            ListarContasDoCliente listarContasDoCliente,
            ConsultarContextoAtendimento consultarContextoAtendimento) {
        this.listarContasDoCliente = listarContasDoCliente;
        this.consultarContextoAtendimento = consultarContextoAtendimento;
    }

    @GetMapping("/clientes/{clienteId}/contas")
    ContasResponse listarContas(@AuthenticationPrincipal Jwt jwt, @PathVariable String clienteId) {
        return ContasResponse.de(listarContasDoCliente.executar(
                new GerenteId(jwt.getSubject()), new ClienteId(clienteId)));
    }

    @GetMapping("/clientes/{clienteId}/contas/{contaId}/contexto-atendimento")
    ContextoAtendimentoResponse consultarContextoAtendimento(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String clienteId,
            @PathVariable String contaId) {

        return ContextoAtendimentoResponse.de(consultarContextoAtendimento.executar(
                new GerenteId(jwt.getSubject()), new ClienteId(clienteId), new ContaId(contaId)));
    }
}
