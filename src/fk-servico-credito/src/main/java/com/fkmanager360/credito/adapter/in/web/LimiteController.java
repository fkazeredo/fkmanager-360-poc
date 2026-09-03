package com.fkmanager360.credito.adapter.in.web;

import com.fkmanager360.credito.application.usecase.ConsultarLimiteChequeEspecialVigente;
import com.fkmanager360.credito.domain.ClienteId;
import com.fkmanager360.credito.domain.ContaId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * AC29 (parcial): o limite apresentado e o LimiteChequeEspecialVigente que o CoreLegado reconhece
 * no momento da consulta, lido pela ACL deste contexto. Nenhum valor local ou derivado e
 * apresentado como limite do Cliente (ADR-0002).
 *
 * <p>O {@code clienteId} faz parte do caminho porque a autorizacao em CarteiraClientes e por
 * Cliente e precisa acontecer <b>antes</b> de qualquer leitura no Core (AC23). Ele nao e aceito
 * como verdade sobre a quem a conta pertence -- essa confirmacao e autoritativa e vem de
 * CarteiraClientes contra o Core.
 */
@RestController
@RequiredArgsConstructor
public class LimiteController {

    private final ConsultarLimiteChequeEspecialVigente consultarLimite;

    @GetMapping("/clientes/{clienteId}/contas/{contaId}/limite-cheque-especial-vigente")
    LimiteChequeEspecialVigenteResponse consultar(
            @PathVariable String clienteId, @PathVariable String contaId) {

        ContaId conta = new ContaId(contaId);
        return LimiteChequeEspecialVigenteResponse.de(
                conta, consultarLimite.executar(new ClienteId(clienteId), conta));
    }
}
