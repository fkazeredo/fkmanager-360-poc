package com.fkmanager360.bffgerente.adapter.in.web;

import java.time.Instant;

/**
 * O que servico-credito devolve: limite em centavos e o instante em que a plataforma o leu do
 * CoreLegado. Nada cadastral -- isso vem do outro contexto.
 */
record LimiteVigenteResponse(String contaId, long limiteChequeEspecialVigente, Instant consultadoEm) {
}
