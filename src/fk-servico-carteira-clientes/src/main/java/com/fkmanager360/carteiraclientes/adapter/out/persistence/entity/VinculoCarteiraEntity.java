package com.fkmanager360.carteiraclientes.adapter.out.persistence.entity;

import com.fkmanager360.carteiraclientes.domain.ClienteId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Espelho JPA de {@code vinculo_carteira} (V1__criar_vinculo_carteira.sql). E adapter, nao
 * dominio (ADR-0020): {@code ClienteId} e {@code GerenteId} -- os tipos que o hexagono conhece --
 * vivem em {@code domain}, sem anotacao alguma; esta classe so existe para o Hibernate ter algo
 * para mapear.
 *
 * <p>Sem {@code equals}/{@code hashCode}: nenhuma colecao deste modulo agrupa ou deduplica
 * instancias desta entity por identidade -- o adapter le linhas e as traduz para
 * {@link ClienteId} antes de qualquer estrutura de dados fazer uso delas, e o padrao default de
 * {@code Object} (identidade de referencia) nunca e exercitado. Adicionar um par gerado por
 * Lombok aqui seria cerimonia sem consumidor.
 */
@Entity
@Table(
        name = "vinculo_carteira",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_vinculo_carteira_gerente_cliente",
                columnNames = {"gerente_id", "cliente_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VinculoCarteiraEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "gerente_id", nullable = false)
    private String gerenteId;

    @Column(name = "cliente_id", nullable = false)
    private String clienteId;

    // Valor vem do DEFAULT now() do banco (migration V1) -- a aplicacao nunca escreve nesta
    // tabela (o seed e migration, V2), entao a coluna e somente-leitura sob JPA.
    @Column(name = "criado_em", insertable = false, updatable = false)
    private Instant criadoEm;

    /**
     * Unica traducao que este modulo precisa: a port devolve {@code PageResult<ClienteId>}, nao
     * a linha inteira -- {@code gerenteId} e {@code criadoEm} nunca atravessam para fora deste
     * adapter.
     */
    public ClienteId toClienteId() {
        return new ClienteId(clienteId);
    }
}
