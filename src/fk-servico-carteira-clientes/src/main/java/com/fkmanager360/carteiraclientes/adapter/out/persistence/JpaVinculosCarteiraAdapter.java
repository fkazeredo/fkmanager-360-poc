package com.fkmanager360.carteiraclientes.adapter.out.persistence;

import com.fkmanager360.carteiraclientes.adapter.out.persistence.entity.VinculoCarteiraEntity;
import com.fkmanager360.carteiraclientes.adapter.out.persistence.repository.VinculoCarteiraRepository;
import com.fkmanager360.carteiraclientes.application.port.out.VinculosCarteiraPort;
import com.fkmanager360.carteiraclientes.domain.ClienteId;
import com.fkmanager360.carteiraclientes.domain.GerenteId;
import com.fkmanager360.carteiraclientes.domain.PageResult;
import com.fkmanager360.carteiraclientes.domain.Pagination;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

/**
 * Adapter de saida sobre o armazenamento privado de CarteiraClientes (ADR-0014). A aplicacao nao
 * cria schema em runtime -- as migrations sao aplicadas por Flyway embutido no proprio boot, com
 * credencial de DDL separada da credencial de DML deste adapter (ADR-0014, emenda 2026-09-02) --
 * este adapter so le linhas, delegando a {@link VinculoCarteiraRepository} (Spring Data JPA).
 */
@Repository
@RequiredArgsConstructor
public class JpaVinculosCarteiraAdapter implements VinculosCarteiraPort {

    private final VinculoCarteiraRepository repository;

    @Override
    public PageResult<ClienteId> findPage(GerenteId gerenteId, Pagination pagination) {
        Page<VinculoCarteiraEntity> pagina = repository.findByGerenteIdOrderByIdAsc(
                gerenteId.valor(), PageRequest.of(pagination.page(), pagination.size()));

        return new PageResult<>(
                pagina.getContent().stream().map(VinculoCarteiraEntity::toClienteId).toList(),
                pagination.page(),
                pagination.size(),
                pagina.getTotalElements());
    }

    @Override
    public boolean existeVinculo(GerenteId gerenteId, ClienteId clienteId) {
        return repository.existsByGerenteIdAndClienteId(gerenteId.valor(), clienteId.valor());
    }
}
