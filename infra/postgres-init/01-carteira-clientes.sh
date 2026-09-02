#!/bin/sh
# Roda uma unica vez, pelo proprio entrypoint da imagem postgres, quando o volume de dados esta
# vazio. Cria o armazenamento privado de servico-carteira-clientes (ADR-0014) e os dois papeis
# que separam privilegio por processo (ADR-0014): carteira_migrator tem DDL e e dono do schema;
# carteira_app so tem DML, mesmo depois que o executor de migrations (F7) criar as tabelas --
# ALTER DEFAULT PRIVILEGES garante isso automaticamente para toda tabela futura criada pelo
# migrator, sem precisar de um segundo passo apos as migrations rodarem.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE ROLE carteira_migrator WITH LOGIN PASSWORD '$CARTEIRA_DB_MIGRATOR_SENHA';
    CREATE ROLE carteira_app WITH LOGIN PASSWORD '$CARTEIRA_DB_APP_SENHA';
    CREATE DATABASE carteira_clientes_db OWNER carteira_migrator;
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname carteira_clientes_db <<-EOSQL
    ALTER SCHEMA public OWNER TO carteira_migrator;
    GRANT CONNECT ON DATABASE carteira_clientes_db TO carteira_app;
    GRANT USAGE ON SCHEMA public TO carteira_app;
    ALTER DEFAULT PRIVILEGES FOR ROLE carteira_migrator IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO carteira_app;
    ALTER DEFAULT PRIVILEGES FOR ROLE carteira_migrator IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO carteira_app;
EOSQL
