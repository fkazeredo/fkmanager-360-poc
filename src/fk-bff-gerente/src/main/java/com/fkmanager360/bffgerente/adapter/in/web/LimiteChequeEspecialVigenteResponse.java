package com.fkmanager360.bffgerente.adapter.in.web;

import java.time.Instant;

/**
 * O que servico-credito devolve: limite em centavos e o instante em que a plataforma o leu do
 * CoreLegado. Nada cadastral -- isso vem do outro contexto.
 *
 * <p>{@code limiteChequeEspecialVigente} e {@code Long} (nao {@code long}) deliberadamente: zero
 * e limite valido -- significa Cliente sem cheque especial, nao ausencia de informacao -- e um
 * primitivo confundiria "campo ausente na resposta" com "limite zero", desserializando um
 * corpo malformado como {@code 0} sem erro algum. {@link AtendimentoResponse#de} valida a
 * presenca antes de expor o valor como fato de negocio.
 */
record LimiteChequeEspecialVigenteResponse(String contaId, Long limiteChequeEspecialVigente, Instant consultadoEm) {
}
