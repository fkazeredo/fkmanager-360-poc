package com.fkmanager360.credito.adapter.out.legacy;

/**
 * Traducao de representacao entre os identificadores deste contexto e o formato host-centric:
 * numeros de 10 digitos com zero-padding para fora, sem padding para dentro (ADR-0005). Vive na
 * ACL porque e a unica fronteira que pode conhecer o formato do host.
 *
 * <p>Copia deliberada da classe homonima de {@code servico-carteira-clientes} (ADR-0011: nenhuma
 * dependencia Java entre bounded contexts, mesmo quando funcionaria) -- nao a mesma preocupacao
 * tecnica que justificaria unificar: esta copia so precisa de {@code toCodigoHost}, porque
 * Credito nunca constroi um identificador a partir de dado vindo do host -- ele so converte um
 * identificador ja recebido (do caminho HTTP) PARA o formato host, nunca o contrario. A copia de
 * CarteiraClientes tambem tem {@code stripLeadingZeros} porque aquele contexto faz o percurso
 * inverso, traduzindo `COD-CLI`/`numCta` do host para o identificador interno. A assimetria entre
 * as duas copias e estrutural, nao um divergencia acidental a corrigir.
 */
final class HostFormat {

    private HostFormat() {
    }

    static String toCodigoHost(String valor) {
        return "%010d".formatted(Long.parseLong(valor));
    }

    /**
     * Dinheiro em centavos, zero-padding a 15 digitos, sem separador (ADR-0005) -- o mesmo formato
     * que {@code simulador-core-legado} ja usa para {@code VLR-LIM-CHQ-ESP} (#0002). #0004 reusa
     * este formato para os dois valores monetarios da instrucao de efetivacao.
     */
    static String toValorMonetarioHost(long centavos) {
        return "%015d".formatted(centavos);
    }
}
