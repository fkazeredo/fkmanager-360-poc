package com.fkmanager360.credito.application.usecase;

import com.fkmanager360.credito.application.port.out.ContaNaoEncontradaException;
import com.fkmanager360.credito.application.port.out.DadosCreditoCorePort;
import com.fkmanager360.credito.application.port.out.DireitoDeAtendimentoPort;
import com.fkmanager360.credito.domain.ClienteId;
import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.DadosCreditoCore;

/**
 * Caso de uso: o gerente ve o LimiteChequeEspecialVigente de uma conta (AC29, parcial neste
 * ticket -- so a leitura do vigente).
 *
 * <p>As duas linhas do metodo estao nesta ordem por exigencia normativa da spec, e nao por
 * conveniencia: <b>a verificacao do direito de atendimento precede qualquer acesso ao Core</b>.
 * Sem direito, a excecao interrompe o fluxo antes de a porta do Core ser tocada -- e e assim que
 * o AC23 se torna verificavel por efeito observavel ("nenhuma chamada ao CoreLegado foi
 * emitida"), e nao por inspecao de codigo.
 *
 * <p>O {@code clienteId} recebido nao e verdade sobre a quem a conta pertence: ele e a chave que
 * permite a CarteiraClientes fazer sua verificacao local antes de qualquer rede. Quem confirma o
 * vinculo conta-cliente, autoritativamente, e CarteiraClientes contra o CoreLegado.
 */
public class ConsultarLimiteChequeEspecialVigente {

    private final DireitoDeAtendimentoPort direitoDeAtendimento;
    private final DadosCreditoCorePort dadosCreditoCore;

    public ConsultarLimiteChequeEspecialVigente(
            DireitoDeAtendimentoPort direitoDeAtendimento, DadosCreditoCorePort dadosCreditoCore) {
        this.direitoDeAtendimento = direitoDeAtendimento;
        this.dadosCreditoCore = dadosCreditoCore;
    }

    public DadosCreditoCore executar(ClienteId clienteId, ContaId contaId) {
        direitoDeAtendimento.confirmarDireitoDeAtendimento(clienteId, contaId);

        return dadosCreditoCore.consultar(contaId)
                .orElseThrow(() -> new ContaNaoEncontradaException(
                        "O CoreLegado nao reconhece a conta " + contaId.valor()));
    }
}
