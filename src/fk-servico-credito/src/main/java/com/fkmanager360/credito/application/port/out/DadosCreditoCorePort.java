package com.fkmanager360.credito.application.port.out;

import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.DadosCreditoCore;

import java.util.Optional;

/**
 * Porta de saida para a ACL propria de Credito sobre o CoreLegado (ADR-0004). Credito e o dono
 * semantico do LimiteChequeEspecial e o consulta diretamente, sem intermediario -- nao existe
 * gateway universal do Core.
 *
 * <p>{@link Optional#empty()} significa "conta desconhecida pelo Core". Falhas de transporte ou
 * de contrato sao sinalizadas pelas excecoes tipadas desta porta, nunca por um {@code COD-RET}
 * vazando para dentro (ADR-0005).
 */
public interface DadosCreditoCorePort {

    Optional<DadosCreditoCore> consultar(ContaId contaId);
}
