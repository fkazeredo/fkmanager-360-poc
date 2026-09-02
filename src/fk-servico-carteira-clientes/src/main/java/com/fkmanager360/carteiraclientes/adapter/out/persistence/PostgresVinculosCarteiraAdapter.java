package com.fkmanager360.carteiraclientes.adapter.out.persistence;

import com.fkmanager360.carteiraclientes.application.port.out.VinculosCarteiraPort;
import com.fkmanager360.carteiraclientes.domain.ClienteId;
import com.fkmanager360.carteiraclientes.domain.GerenteId;
import com.fkmanager360.carteiraclientes.domain.PageResult;
import com.fkmanager360.carteiraclientes.domain.Pagination;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Adapter de saida sobre o armazenamento privado de CarteiraClientes (ADR-0014). A aplicacao nao
 * cria schema em runtime -- as migrations sao aplicadas por Flyway embutido no proprio boot, com
 * credencial de DDL separada da credencial de DML deste adapter (ADR-0014, emenda 2026-09-02) --
 * este adapter so le e escreve linhas.
 */
@Repository
public class PostgresVinculosCarteiraAdapter implements VinculosCarteiraPort {

    private final JdbcClient jdbcClient;

    public PostgresVinculosCarteiraAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public PageResult<ClienteId> findPage(GerenteId gerenteId, Pagination pagination) {
        long total = jdbcClient.sql("select count(*) from vinculo_carteira where gerente_id = :gerenteId")
                .param("gerenteId", gerenteId.valor())
                .query(Long.class)
                .single();

        List<ClienteId> items = jdbcClient.sql("""
                        select cliente_id from vinculo_carteira
                        where gerente_id = :gerenteId
                        order by id
                        limit :limite offset :deslocamento
                        """)
                .param("gerenteId", gerenteId.valor())
                .param("limite", pagination.size())
                .param("deslocamento", pagination.offset())
                .query((rs, rowNum) -> new ClienteId(rs.getString("cliente_id")))
                .list();

        return new PageResult<>(items, pagination.page(), pagination.size(), total);
    }
}
