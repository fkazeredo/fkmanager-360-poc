package com.fkmanager360.carteiraclientes.migracoes;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Executor one-shot de migrations de {@code servico-carteira-clientes} (ADR-0014). Nao e uma
 * aplicacao Spring: sobe, migra com uma credencial de DDL propria, imprime o resultado e termina
 * com exit status coerente -- exatamente o contrato que o Compose espera de um container
 * one-shot antes de liberar a API.
 */
public final class MigradorApp {

    private MigradorApp() {
    }

    public static void main(String[] args) {
        String url = envObrigatoria("CARTEIRA_DB_JDBC_URL");
        String usuario = envObrigatoria("CARTEIRA_DB_MIGRATOR_USUARIO");
        String senha = envObrigatoria("CARTEIRA_DB_MIGRATOR_SENHA");

        Flyway flyway = Flyway.configure()
                .dataSource(url, usuario, senha)
                .locations("classpath:db/migration")
                .loggers("slf4j")
                .load();

        try {
            MigrateResult resultado = flyway.migrate();
            System.out.printf(
                    "Migracoes aplicadas: %d (schema em %s -> %s)%n",
                    resultado.migrationsExecuted, resultado.initialSchemaVersion, resultado.targetSchemaVersion);
            System.exit(0);
        } catch (FlywayException e) {
            System.err.println("Falha ao migrar servico-carteira-clientes: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String envObrigatoria(String nome) {
        String valor = System.getenv(nome);
        if (valor == null || valor.isBlank()) {
            System.err.println("Variavel de ambiente obrigatoria ausente: " + nome);
            System.exit(2);
        }
        return valor;
    }
}
