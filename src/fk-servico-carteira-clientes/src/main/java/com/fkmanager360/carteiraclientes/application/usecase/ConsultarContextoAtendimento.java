package com.fkmanager360.carteiraclientes.application.usecase;

import com.fkmanager360.carteiraclientes.application.port.out.DadosMestresClientePort;
import com.fkmanager360.carteiraclientes.domain.ClienteId;
import com.fkmanager360.carteiraclientes.domain.ContaCorrente;
import com.fkmanager360.carteiraclientes.domain.ContaId;
import com.fkmanager360.carteiraclientes.domain.ContextoAtendimento;
import com.fkmanager360.carteiraclientes.domain.DadosMestresCliente;
import com.fkmanager360.carteiraclientes.domain.GerenteId;

import java.util.List;

/**
 * Caso de uso: o contexto de atendimento de uma conta para a COMPOSICAO da tela do bff-gerente --
 * quem e o Cliente, com nome e CPF, e qual conta dele esta sendo atendida.
 *
 * <p>Compoe {@link ConfirmarDireitoDeAtendimento} (o primitivo de autorizacao, AC23) com a
 * consulta de dados mestres. Consumidores que precisam apenas confirmar que o atendimento e
 * legitimo -- como servico-credito -- usam {@link ConfirmarDireitoDeAtendimento} diretamente, sem
 * pagar a consulta cadastral nem ficar acoplados a disponibilidade dela; este caso de uso existe
 * para quem, como o bff-gerente, precisa mesmo do nome e do CPF para compor a tela (AC30).
 */
public class ConsultarContextoAtendimento {

    private final ConfirmarDireitoDeAtendimento confirmarDireitoDeAtendimento;
    private final DadosMestresClientePort dadosMestres;

    public ConsultarContextoAtendimento(
            ConfirmarDireitoDeAtendimento confirmarDireitoDeAtendimento, DadosMestresClientePort dadosMestres) {
        this.confirmarDireitoDeAtendimento = confirmarDireitoDeAtendimento;
        this.dadosMestres = dadosMestres;
    }

    public ContextoAtendimento executar(GerenteId gerenteId, ClienteId clienteId, ContaId contaId) {
        ContaCorrente conta = confirmarDireitoDeAtendimento.executar(gerenteId, clienteId, contaId);

        DadosMestresCliente dados = dadosMestres.buscarDadosMestres(List.of(clienteId))
                .getOrDefault(clienteId, DadosMestresCliente.indisponivel());

        return new ContextoAtendimento(clienteId, dados, conta);
    }
}
