package com.fkmanager360.carteiraclientes.adapter.in.web;

import com.fkmanager360.carteiraclientes.application.usecase.ConfirmarDireitoDeAtendimento;
import com.fkmanager360.carteiraclientes.application.usecase.ConsultarContextoAtendimento;
import com.fkmanager360.carteiraclientes.application.usecase.ListarContasDoCliente;
import com.fkmanager360.carteiraclientes.domain.ClienteId;
import com.fkmanager360.carteiraclientes.domain.ContaId;
import com.fkmanager360.carteiraclientes.domain.GerenteId;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * AC22, AC23 e AC30: selecionar um Cliente devolve suas ContaCorrentes; a autorizacao de recurso
 * e produzida aqui -- pelo backend dono da associacao --, nunca pelo app-gerente ou pelo
 * bff-gerente (ADR-0007).
 *
 * <p>Duas operacoes de atendimento por conta, com publicos diferentes de proposito:
 * {@code /direito-de-atendimento} confirma a legitimidade do atendimento sem devolver nada
 * cadastral -- e o que servico-credito consome, para que uma falha na consulta de dados mestres
 * do Cliente nunca impeca a leitura do limite; {@code /contexto-atendimento} devolve o contexto
 * rico -- nome, CPF, conta -- que o bff-gerente usa para compor a tela (AC30).
 *
 * <p>O {@code gerenteId} vem do claim {@code sub} do token ja validado na borda e nunca de
 * parametro algum; o {@code clienteId} vem do caminho, e existe justamente para que a
 * verificacao de direito possa acontecer <b>antes</b> de qualquer chamada ao CoreLegado. Ele nao
 * e tratado como verdade sobre a quem a conta pertence -- isso quem afirma e o Core.
 */
@RestController
@RequiredArgsConstructor
public class AtendimentoController {

    private final ListarContasDoCliente listarContasDoCliente;
    private final ConfirmarDireitoDeAtendimento confirmarDireitoDeAtendimento;
    private final ConsultarContextoAtendimento consultarContextoAtendimento;

    @GetMapping("/clientes/{clienteId}/contas")
    ContasResponse listarContas(@AuthenticationPrincipal Jwt jwt, @PathVariable String clienteId) {
        return ContasResponse.de(listarContasDoCliente.executar(
                new GerenteId(jwt.getSubject()), new ClienteId(clienteId)));
    }

    /**
     * Confirmacao estreita do direito de atendimento: 204 quando legitimo, 403 sem vinculo com o
     * Cliente, 404 quando a conta nao e dele. Nenhum corpo, porque nenhum consumidor deste
     * endpoint precisa de mais do que a resposta binaria -- os dois identificadores ja vieram no
     * caminho da propria requisicao.
     */
    @GetMapping("/clientes/{clienteId}/contas/{contaId}/direito-de-atendimento")
    ResponseEntity<Void> confirmarDireitoDeAtendimento(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String clienteId,
            @PathVariable String contaId) {

        confirmarDireitoDeAtendimento.executar(
                new GerenteId(jwt.getSubject()), new ClienteId(clienteId), new ContaId(contaId));
        return ResponseEntity.noContent().build();
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
