package com.fkmanager360.credito.application.port.out;

/**
 * O {@code limiteVigenteVisto} enviado pelo gerente diverge do {@code LimiteChequeEspecialVigente}
 * relido agora no CoreLegado (passo 6/9 da Fase 0 do plano #0003). A borda traduz para {@code 409}
 * com o codigo estavel {@code LIMITE_VIGENTE_DESATUALIZADO}.
 *
 * <p>Esta excecao PRECEDE deliberadamente {@link LimiteSolicitadoNaoAumentaException} na ordem de
 * avaliacao -- o caso decisivo da spec: gerente viu 5.000, Core ja em 6.000, pedido de 5.500 e
 * sempre {@code 409}, nunca {@code 422} "nao aumenta o limite", porque essa seria uma comparacao
 * que o gerente nao fez com o valor que realmente esta vigente agora.
 */
public class LimiteVigenteDesatualizadoException extends RuntimeException implements ErroDeAplicacaoComCodigo {

    public static final String CODIGO = "LIMITE_VIGENTE_DESATUALIZADO";

    public LimiteVigenteDesatualizadoException(String message) {
        super(message);
    }

    @Override
    public String codigo() {
        return CODIGO;
    }
}
