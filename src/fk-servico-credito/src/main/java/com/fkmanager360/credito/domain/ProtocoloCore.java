package com.fkmanager360.credito.domain;

/**
 * Identificador devolvido pelo CoreLegado ao aceitar uma instrucao de EfetivacaoLimite
 * (CONTEXT.md de Credito). Pode existir sem ser conhecido por Credito quando a resposta de
 * aceite se perde; nesse caso a recuperacao e pelo {@link EfetivacaoId} (ADR-0009).
 *
 * <p>String opaca desta plataforma: o formato concreto (zero-padding, tamanho) e vocabulario
 * host-centric e permanece encapsulado na ACL (ADR-0005) -- este tipo so exige presenca.
 */
public record ProtocoloCore(String valor) {

    public ProtocoloCore {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("ProtocoloCore nao pode ser vazio");
        }
    }
}
