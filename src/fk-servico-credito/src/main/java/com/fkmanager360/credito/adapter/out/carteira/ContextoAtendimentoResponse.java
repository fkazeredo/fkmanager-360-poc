package com.fkmanager360.credito.adapter.out.carteira;

/**
 * O que Credito le da resposta de CarteiraClientes: o {@code clienteId} autoritativo, e nada
 * mais.
 *
 * <p>A operacao remota devolve tambem nome, CPF mascarado e identificacao da conta, porque o
 * bff-gerente precisa deles para compor a tela. Este record declara apenas o campo que este
 * contexto usa -- os demais nem chegam a ser desserializados. E a forma executavel do AC30:
 * Credito nao conhece dado cadastral do Cliente, e nao passa a conhecer so porque ele trafegou.
 */
record ContextoAtendimentoResponse(String clienteId) {
}
