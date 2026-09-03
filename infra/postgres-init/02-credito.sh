#!/bin/sh
# Roda uma unica vez, pelo proprio entrypoint da imagem postgres, quando o volume de dados esta
# vazio. Cria o armazenamento privado de servico-credito (ADR-0014) e os dois papeis que separam
# privilegio por processo (ADR-0014): credito_migrator tem DDL e e dono do schema; credito_app so
# tem DML, mesmo depois que o Flyway embutido (ADR-0014, emenda 2026-09-02) criar as tabelas --
# ALTER DEFAULT PRIVILEGES garante isso automaticamente para toda tabela futura criada pelo
# migrator, sem precisar de um segundo passo apos as migrations rodarem.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE ROLE credito_migrator WITH LOGIN PASSWORD '$CREDITO_DB_MIGRATOR_PASSWORD';
    CREATE ROLE credito_app WITH LOGIN PASSWORD '$CREDITO_DB_APP_PASSWORD';
    CREATE DATABASE credito_db OWNER credito_migrator;
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname credito_db <<-EOSQL
    ALTER SCHEMA public OWNER TO credito_migrator;
    GRANT CONNECT ON DATABASE credito_db TO credito_app;
    GRANT USAGE ON SCHEMA public TO credito_app;
    ALTER DEFAULT PRIVILEGES FOR ROLE credito_migrator IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO credito_app;
    ALTER DEFAULT PRIVILEGES FOR ROLE credito_migrator IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO credito_app;
EOSQL
