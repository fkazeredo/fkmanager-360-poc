package com.fkmanager360.carteiraclientes.migracoes;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova, contra PostgreSQL real, que as migrations aplicam e que a base de demonstracao fica
 * coerente: sete clientes para o gerente A, tres para o gerente B, e nenhum vinculo para o
 * cliente sem carteira. servico-carteira-clientes reusa exatamente estes scripts (nao uma copia)
 * no seu proprio S3, via recurso de teste adicional apontando para este modulo.
 */
@Testcontainers
class MigracoesTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Test
    void migracoes_aplicam_e_semeiam_dados_deterministicos() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();

        MigrateResult resultado = flyway.migrate();
        assertThat(resultado.success).isTrue();
        assertThat(resultado.migrationsExecuted).isEqualTo(2);

        try (Connection conexao = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = conexao.createStatement()) {

            assertThat(contar(statement, "select count(*) from vinculo_carteira where gerente_id = 'gerente.a'"))
                    .isEqualTo(7);
            assertThat(contar(statement, "select count(*) from vinculo_carteira where gerente_id = 'gerente.b'"))
                    .isEqualTo(3);
            assertThat(contar(statement, "select count(*) from vinculo_carteira where cliente_id = '999'"))
                    .isEqualTo(0);
        }
    }

    private static long contar(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
