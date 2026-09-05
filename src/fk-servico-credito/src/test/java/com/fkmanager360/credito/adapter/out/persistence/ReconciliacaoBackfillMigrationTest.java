package com.fkmanager360.credito.adapter.out.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3 (ADR-0018): a semantica temporal do backfill de V3 (#0006) -- o relogio da agenda de
 * reconciliacao deriva do instante HISTORICO do nascimento da intencao (
 * {@code outbox_mensagem.criado_em}), nunca de {@code now()} da propria migration. Teste isolado,
 * com container proprio: precisa aplicar V1+V2, inserir uma fixture com um {@code criado_em}
 * deliberadamente antigo, e SO ENTAO completar a migracao ate V3 -- um cenario que a suite
 * principal ({@code JdbcReconciliacaoEfetivacaoAdapterTest}) nao pode reproduzir, porque ali as
 * tres migrations ja rodam juntas antes de qualquer fixture existir.
 */
@Testcontainers
class ReconciliacaoBackfillMigrationTest {

    private static final String SUPERUSER_PASSWORD_DB = "credito_db";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    @Test
    void backfillV3_derivaOReloAgoDoInstanteHistoricoDeCriadoEm_naoDeNowDaMigration() throws SQLException {
        String enderecoContainer = enderecoNaRedeBridge(POSTGRES);
        String superuserJdbcUrl = "jdbc:postgresql://" + enderecoContainer + ":5432/" + POSTGRES.getDatabaseName();
        String creditoJdbcUrl = "jdbc:postgresql://" + enderecoContainer + ":5432/" + SUPERUSER_PASSWORD_DB;

        try (Connection superusuario = conectarComRetry(superuserJdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = superusuario.createStatement()) {
            stmt.execute("create database " + SUPERUSER_PASSWORD_DB);
        }

        // V1 + V2 apenas -- o schema antes de #0006 existir.
        Flyway.configure()
                .dataSource(creditoJdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("2"))
                .load()
                .migrate();

        // Fixture: uma SolicitacaoAumentoLimite APROVADA "ha muito tempo" -- criado_em de
        // outbox_mensagem deliberadamente distante de "agora" (a migration V3 vai rodar em
        // instantes; se o backfill usasse now(), a agenda refletiria ISSO, nao a idade real).
        UUID solicitacaoId = UUID.randomUUID();
        UUID efetivacaoId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant criadoEmHistorico = Instant.parse("2026-08-01T08:00:00Z"); // dias antes desta execucao
        Timestamp criadoEmTs = Timestamp.from(criadoEmHistorico);

        try (Connection con = DriverManager.getConnection(creditoJdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
            executar(con, """
                    insert into solicitacao_aumento_limite
                        (id, cliente_id, conta_id, originador_id, origem_solicitacao, canal_manifestacao,
                         status, correlation_id, efetivacao_id, registrada_em, atualizada_em)
                    values (?, '1', '9999990001', 'gerente.teste', 'CLIENTE', 'PRESENCIAL',
                            'AGUARDANDO_EFETIVACAO', ?, ?, ?, ?)
                    """, solicitacaoId, correlationId, efetivacaoId, criadoEmTs, criadoEmTs);

            executar(con, """
                    insert into contexto_decisao_credito
                        (solicitacao_id, limite_cheque_especial_vigente, situacao_conta,
                         classificacao_risco_credito_base, limite_solicitado, incremento_solicitado,
                         versao_politica_credito, capturado_em, dados_credito_core_fonte, dados_credito_core_consultado_em)
                    values (?, 500000, 'REGULAR', 'BAIXO', 600000, 100000, 'v1', ?, 'CORE_LEGADO', ?)
                    """, solicitacaoId, criadoEmTs, criadoEmTs);

            executar(con, """
                    insert into decisao_credito
                        (solicitacao_id, resultado, motivo, versao_politica_credito, decidida_em, autor_tipo, autor_id)
                    values (?, 'APROVADA', 'DENTRO_DA_POLITICA_AUTOMATICA', 'v1', ?, 'SISTEMA', 'MOTOR_DECISAO_CREDITO')
                    """, solicitacaoId, criadoEmTs);

            executar(con, """
                    insert into outbox_mensagem
                        (message_id, tipo, destino, solicitacao_id, efetivacao_id, conta_id,
                         limite_cheque_especial_vigente_esperado, limite_solicitado, correlation_id, criado_em)
                    values (?, 'EfetivarLimite', 'CORE_LEGADO', ?, ?, '9999990001', 500000, 600000, ?, ?)
                    """, messageId, solicitacaoId, efetivacaoId, correlationId, criadoEmTs);
        }

        // Completa a migracao ate a versao mais recente (V3) -- o backfill roda AGORA, mas precisa
        // agendar como se a intencao tivesse nascido em criadoEmHistorico.
        Flyway.configure()
                .dataSource(creditoJdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        Timestamp proximaConsultaEm;
        Timestamp janelaExpiraEm;
        try (Connection con = DriverManager.getConnection(creditoJdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = con.prepareStatement(
                     "select proxima_consulta_em, janela_expira_em, status_reconciliacao "
                             + "from reconciliacao_efetivacao where efetivacao_id = ?")) {
            ps.setObject(1, efetivacaoId);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).as("backfill deveria ter criado uma linha para a solicitacao AGUARDANDO_EFETIVACAO").isTrue();
                proximaConsultaEm = rs.getTimestamp("proxima_consulta_em");
                janelaExpiraEm = rs.getTimestamp("janela_expira_em");
                assertThat(rs.getString("status_reconciliacao")).isEqualTo("PENDENTE");
            }
        }

        // O relogio do backfill deriva do EVENTO HISTORICO (criado_em), nao de now() da migration --
        // por isso a solicitacao antiga nasce com janela ja vencida ha muito tempo, exatamente como
        // a spec exige ("preserva a idade real da operacao").
        assertThat(proximaConsultaEm.toInstant()).isEqualTo(criadoEmHistorico.plus(Duration.ofSeconds(60)));
        assertThat(janelaExpiraEm.toInstant()).isEqualTo(criadoEmHistorico.plus(Duration.ofMinutes(10)));
        assertThat(janelaExpiraEm.toInstant()).isBefore(Instant.now());
    }

    private static Connection conectarComRetry(String url, String user, String password) throws SQLException {
        SQLException ultimaFalha = null;
        for (int tentativa = 0; tentativa < 20; tentativa++) {
            try {
                return DriverManager.getConnection(url, user, password);
            } catch (SQLException e) {
                ultimaFalha = e;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrompida) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw ultimaFalha;
    }

    private static String enderecoNaRedeBridge(org.testcontainers.containers.GenericContainer<?> container) {
        return container.getContainerInfo().getNetworkSettings().getNetworks().get("bridge").getIpAddress();
    }

    private static void executar(Connection con, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.execute();
        }
    }
}
