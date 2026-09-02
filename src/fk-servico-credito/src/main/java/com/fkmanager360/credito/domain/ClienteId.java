package com.fkmanager360.credito.domain;

/**
 * Identidade do Cliente como Credito a conhece: um identificador, e nada mais. Este contexto nao
 * e dono de dado cadastral -- nome, CPF e agencia pertencem a CarteiraClientes (AC30).
 *
 * <p>Nao e o mesmo tipo Java de {@code CarteiraClientes.ClienteId}, deliberadamente: entidades de
 * dominio nunca atravessam bounded contexts (ADR-0011). A coincidencia de forma e do
 * identificador de negocio, nao acoplamento de codigo.
 */
public record ClienteId(String valor) {

    public ClienteId {
        if (valor == null || !valor.matches("[0-9]{1,10}")) {
            throw new IllegalArgumentException("ClienteId deve ser numerico, com ate 10 digitos: " + valor);
        }
    }
}
