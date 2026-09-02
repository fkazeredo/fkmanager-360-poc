package com.fkmanager360.carteiraclientes.migrations;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Executor one-shot de migrations de {@code servico-carteira-clientes} (ADR-0014). Nao e uma
 * aplicacao Spring: sobe, migra com uma credencial de DDL propria, imprime o resultado e termina
 * com exit status coerente -- exatamente o contrato que o Compose espera de um container
 * one-shot antes de liberar a API.
 */
public final class MigrationRunner {

    private MigrationRunner() {
    }

    public static void main(String[] args) {
        String url = requiredEnv("CARTEIRA_DB_JDBC_URL");
        String user = requiredEnv("CARTEIRA_DB_MIGRATOR_USER");
        String password = requiredEnv("CARTEIRA_DB_MIGRATOR_PASSWORD");

        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .loggers("slf4j")
                .load();

        try {
            MigrateResult result = flyway.migrate();
            System.out.printf(
                    "Migracoes aplicadas: %d (schema em %s -> %s)%n",
                    result.migrationsExecuted, result.initialSchemaVersion, result.targetSchemaVersion);
            System.exit(0);
        } catch (FlywayException e) {
            System.err.println("Falha ao migrar servico-carteira-clientes: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            System.err.println("Variavel de ambiente obrigatoria ausente: " + name);
            System.exit(2);
        }
        return value;
    }
}
