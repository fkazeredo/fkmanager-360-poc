package com.fkmanager360.credito.domain;

/**
 * O LimiteChequeEspecial atualmente reconhecido pelo CoreLegado -- o unico valor que pode ser
 * apresentado como "o limite do Cliente" (ADR-0002).
 *
 * <p>Em centavos, como inteiro: dinheiro nao usa ponto flutuante em nenhuma camada deste sistema
 * (ADR-0005). Zero e limite valido -- significa Cliente sem cheque especial, nao ausencia de
 * informacao; negativo nao existe no contrato do host e seria sinal de resposta corrompida.
 */
public record LimiteChequeEspecialVigente(long centavos) {

    public LimiteChequeEspecialVigente {
        if (centavos < 0) {
            throw new IllegalArgumentException("LimiteChequeEspecialVigente nao pode ser negativo: " + centavos);
        }
    }
}
