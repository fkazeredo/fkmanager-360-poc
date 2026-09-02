package com.fkmanager360.carteiraclientes.adapters.saida.persistencia;

import com.fkmanager360.carteiraclientes.aplicacao.portas.PortaVinculosCarteira;
import com.fkmanager360.carteiraclientes.dominio.ClienteId;
import com.fkmanager360.carteiraclientes.dominio.GerenteId;
import com.fkmanager360.carteiraclientes.dominio.PaginaResultado;
import com.fkmanager360.carteiraclientes.dominio.Paginacao;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Adapter de saida sobre o armazenamento privado de CarteiraClientes (ADR-0014). A aplicacao nao
 * cria schema em runtime -- as migrations sao aplicadas antes, pelo executor one-shot
 * (ADR-0014) -- este adapter so le e escreve linhas.
 */
@Repository
public class PostgresPortaVinculosCarteira implements PortaVinculosCarteira {

    private final JdbcClient jdbcClient;

    public PostgresPortaVinculosCarteira(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public PaginaResultado<ClienteId> buscarPagina(GerenteId gerenteId, Paginacao paginacao) {
        long total = jdbcClient.sql("select count(*) from vinculo_carteira where gerente_id = :gerenteId")
                .param("gerenteId", gerenteId.valor())
                .query(Long.class)
                .single();

        List<ClienteId> itens = jdbcClient.sql("""
                        select cliente_id from vinculo_carteira
                        where gerente_id = :gerenteId
                        order by id
                        limit :limite offset :deslocamento
                        """)
                .param("gerenteId", gerenteId.valor())
                .param("limite", paginacao.tamanho())
                .param("deslocamento", paginacao.deslocamento())
                .query((rs, rowNum) -> new ClienteId(rs.getString("cliente_id")))
                .list();

        return new PaginaResultado<>(itens, paginacao.pagina(), paginacao.tamanho(), total);
    }
}
