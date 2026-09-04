package com.fkmanager360.credito.application.port.out;

/**
 * Porta de saida para a entrega da instrucao de efetivacao ao CoreLegado (plano #0004, secao 6).
 * O adapter que implementa esta porta e a ACL de efetivacao -- o unico lugar que conhece
 * {@code COD-RET}, campos host, padding e o protocolo do simulador (ADR-0005). Nunca lanca
 * excecao para o chamador: toda patologia observavel e classificada em {@link ResultadoInstrucaoCore}.
 */
public interface InstrucaoEfetivacaoCorePort {

    ResultadoInstrucaoCore entregar(IntencaoEfetivacao intencao);
}
