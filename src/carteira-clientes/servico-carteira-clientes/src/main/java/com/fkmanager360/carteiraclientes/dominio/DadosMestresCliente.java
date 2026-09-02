package com.fkmanager360.carteiraclientes.dominio;

/**
 * O que este contexto precisa dos dados mestres do {@code Cliente}, ja traduzido do vocabulario
 * host-centric pela ACL (ADR-0004). Deliberadamente minimo: nome e CPF mascarado, o suficiente
 * para o gerente reconhecer o cliente na listagem. Nenhum dado financeiro -- este contexto nao e
 * fachada financeira e nao conhece LimiteChequeEspecial (AC30).
 */
public record DadosMestresCliente(String nome, String cpfMascarado) {

    /**
     * Representa um {@code Cliente} cujos dados mestres nao puderam ser obtidos do CoreLegado no
     * momento da composicao -- anomalia de consistencia entre a associacao local e o Core, nao um
     * caso de negocio esperado. A listagem continua utilizavel em vez de falhar por inteiro.
     */
    public static DadosMestresCliente indisponivel() {
        return new DadosMestresCliente("(dados indisponiveis)", "");
    }
}
