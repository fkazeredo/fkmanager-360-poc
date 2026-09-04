package com.fkmanager360.credito.adapter.out.persistence;

import com.fkmanager360.credito.application.port.out.TransacaoPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * Implementacao declarativa de {@link TransacaoPort}: o {@code @Transactional} abre a transacao e
 * a {@code unidade} roda inteira dentro dela -- as portas chamadas la dentro ({@code
 * ResultadoEfetivacaoPort} com propagacao {@code REQUIRED}, e as operacoes {@code MANDATORY} de
 * {@code EntregasEfetivacaoPort}) juntam-se a esta mesma transacao. Sem {@code
 * TransactionTemplate}: a forma declarativa e a unica usada neste modulo, e as razoes documentadas
 * contra o template em TX1/TX2 continuam valendo.
 *
 * <p>Vive em {@code adapter.out.persistence} por exigencia do ArchUnit ({@code
 * transacao_somente_na_persistencia}): e o unico pacote autorizado a depender de
 * {@code org.springframework.transaction}.
 */
@Component
class TransacaoAdapter implements TransacaoPort {

    @Override
    @Transactional
    public <T> T executar(Supplier<T> unidade) {
        return unidade.get();
    }
}
