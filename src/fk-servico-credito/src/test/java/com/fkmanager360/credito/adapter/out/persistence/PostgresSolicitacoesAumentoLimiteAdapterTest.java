package com.fkmanager360.credito.adapter.out.persistence;

import com.fkmanager360.credito.application.port.out.CargaParaDecisao;
import com.fkmanager360.credito.application.port.out.EntradaHistorico;
import com.fkmanager360.credito.application.port.out.IdempotenciaEmProcessamentoException;
import com.fkmanager360.credito.application.port.out.IntencaoEfetivacao;
import com.fkmanager360.credito.application.port.out.NovaSolicitacaoAumentoLimite;
import com.fkmanager360.credito.application.port.out.RegistroIdempotenteEncontrado;
import com.fkmanager360.credito.application.port.out.ResultadoAplicacaoDecisao;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroSolicitacao;
import com.fkmanager360.credito.application.port.out.SolicitacaoCriada;
import com.fkmanager360.credito.application.port.out.SolicitacaoNaoEncontradaException;
import com.fkmanager360.credito.application.port.out.SolicitacaoNaoTerminalExistente;
import com.fkmanager360.credito.application.port.out.TipoFatoHistorico;
import com.fkmanager360.credito.domain.AtorId;
import com.fkmanager360.credito.domain.AtorSistema;
import com.fkmanager360.credito.domain.CanalManifestacao;
import com.fkmanager360.credito.domain.ClassificacaoRiscoCreditoBase;
import com.fkmanager360.credito.domain.ClienteId;
import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.ContextoDecisaoCredito;
import com.fkmanager360.credito.domain.CorrelationId;
import com.fkmanager360.credito.domain.DadosCreditoCore;
import com.fkmanager360.credito.domain.DecisaoCredito;
import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.IdempotencyKey;
import com.fkmanager360.credito.domain.LimiteChequeEspecialVigente;
import com.fkmanager360.credito.domain.LimiteSolicitado;
import com.fkmanager360.credito.domain.ManifestacaoCliente;
import com.fkmanager360.credito.domain.MotivoDecisaoCredito;
import com.fkmanager360.credito.domain.OrigemSolicitacao;
import com.fkmanager360.credito.domain.ResultadoDecisaoCredito;
import com.fkmanager360.credito.domain.SituacaoConta;
import com.fkmanager360.credito.domain.SolicitacaoId;
import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;
import com.fkmanager360.credito.domain.VersaoPoliticaCredito;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S3 (ADR-0018): comportamento de persistencia de Credito provado contra PostgreSQL real via
 * Testcontainers, aplicando literalmente {@code infra/postgres-init/02-credito.sh} (adaptado para
 * Java) e as migrations reais de {@code src/main/resources/db/migration}. H2 nao substitui.
 *
 * <p><b>Diferenca deliberada em relacao a {@code PostgresVinculosCarteiraAdapterTest}</b>
 * (CarteiraClientes): aquele teste roda Flyway e o {@code JdbcClient} de teste com o MESMO
 * superusuario do container, o que nunca prova separacao real de privilegio. Aqui, DEPOIS de o
 * container subir, conectamos com o superusuario apenas para criar {@code credito_migrator} e
 * {@code credito_app} exatamente como o script de init faria; Flyway roda com {@code credito_migrator};
 * e {@link #adapter}, usado por TODOS os testes funcionais abaixo, roda exclusivamente com
 * {@code credito_app} -- os testes de unicidade, idempotencia e atomicidade ja rodam sob o
 * privilegio real de producao, nao sob um superusuario que mascararia um problema de GRANT.
 */
@Testcontainers
class PostgresSolicitacoesAumentoLimiteAdapterTest {

    private static final String MIGRATOR_USER = "credito_migrator";
    private static final String MIGRATOR_PASSWORD = "migrator-teste-nao-usar-em-producao";
    private static final String APP_USER = "credito_app";
    private static final String APP_PASSWORD = "app-teste-nao-usar-em-producao";

    private static final List<String> TODAS_AS_TABELAS = List.of(
            "solicitacao_aumento_limite", "contexto_decisao_credito", "decisao_credito",
            "registro_idempotencia", "outbox_mensagem", "historico_solicitacao");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static String creditoJdbcUrl;
    private static HikariDataSource appDataSource;
    private static JdbcClient appJdbcClient;
    private static PostgresRegistroIdempotenciaAdapter registroIdempotenciaAdapter;
    private static PostgresSolicitacoesAumentoLimiteAdapter adapter;

    private static long contaSequencial = 9_100_000_000L;

    @BeforeAll
    static void provisionarPapeisMigrarESubirAdapterComCredencialDeApp() throws Exception {
        creditoJdbcUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/credito_db";

        // Espelha infra/postgres-init/02-credito.sh literalmente, so que em Java: o superusuario
        // do Testcontainers e usado APENAS aqui, para criar os dois papeis e o database -- nunca
        // pelos testes funcionais abaixo.
        try (Connection superusuario = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = superusuario.createStatement()) {
            stmt.execute("create role " + MIGRATOR_USER + " with login password '" + MIGRATOR_PASSWORD + "'");
            stmt.execute("create role " + APP_USER + " with login password '" + APP_PASSWORD + "'");
            stmt.execute("create database credito_db owner " + MIGRATOR_USER);
        }

        try (Connection superusuarioNoCreditoDb = DriverManager.getConnection(
                creditoJdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = superusuarioNoCreditoDb.createStatement()) {
            stmt.execute("alter schema public owner to " + MIGRATOR_USER);
            stmt.execute("grant connect on database credito_db to " + APP_USER);
            stmt.execute("grant usage on schema public to " + APP_USER);
            stmt.execute("alter default privileges for role " + MIGRATOR_USER + " in schema public "
                    + "grant select, insert, update, delete on tables to " + APP_USER);
            stmt.execute("alter default privileges for role " + MIGRATOR_USER + " in schema public "
                    + "grant usage, select on sequences to " + APP_USER);
        }

        // Migracao embutida (ADR-0014, emenda 2026-09-02): SO com a credencial de DDL.
        Flyway.configure()
                .dataSource(creditoJdbcUrl, MIGRATOR_USER, MIGRATOR_PASSWORD)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // A partir daqui, TUDO que os testes funcionais usam roda com a credencial de DML.
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(creditoJdbcUrl);
        config.setUsername(APP_USER);
        config.setPassword(APP_PASSWORD);
        config.setMaximumPoolSize(10);
        appDataSource = new HikariDataSource(config);

        appJdbcClient = JdbcClient.create(appDataSource);
        registroIdempotenciaAdapter = new PostgresRegistroIdempotenciaAdapter(appJdbcClient);
        adapter = new PostgresSolicitacoesAumentoLimiteAdapter(
                appJdbcClient, new DataSourceTransactionManager(appDataSource), registroIdempotenciaAdapter);
    }

    @AfterAll
    static void fecharDataSource() {
        appDataSource.close();
    }

    // ---------------------------------------------------------------------------------------
    // Guardrail de privilegios reais: migrator tem DDL, app nao tem DDL, app insere nas 6 tabelas
    // (a critica: historico_solicitacao, cuja PK e GENERATED ALWAYS AS IDENTITY).
    // ---------------------------------------------------------------------------------------

    @Test
    void migrator_temPrivilegioDeDdl_conseguindoCriarEDerrubarTabela() throws SQLException {
        try (Connection conMigrator = DriverManager.getConnection(creditoJdbcUrl, MIGRATOR_USER, MIGRATOR_PASSWORD);
             Statement stmt = conMigrator.createStatement()) {
            stmt.execute("create table zz_privilegio_smoke_migrator (id int)");
            stmt.execute("drop table zz_privilegio_smoke_migrator");
        }
    }

    @Test
    void app_naoTemPrivilegioDeDdl_falhaAoTentarCriarTabela() {
        assertThatThrownBy(() -> {
            try (Connection conApp = DriverManager.getConnection(creditoJdbcUrl, APP_USER, APP_PASSWORD);
                 Statement stmt = conApp.createStatement()) {
                stmt.execute("create table zz_privilegio_smoke_app (id int)");
            }
        }).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }

    @Test
    void app_naoTemPrivilegioDeDdl_falhaAoTentarAlterarTabelaExistente() {
        assertThatThrownBy(() -> {
            try (Connection conApp = DriverManager.getConnection(creditoJdbcUrl, APP_USER, APP_PASSWORD);
                 Statement stmt = conApp.createStatement()) {
                stmt.execute("alter table solicitacao_aumento_limite add column zz_smoke varchar(1)");
            }
        }).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }

    @Test
    void app_conseguerInserirEmTodasAsSeisTabelas_incluindoHistoricoComIdentity() throws SQLException {
        UUID id = UUID.randomUUID();
        ContaId contaPriv = novaContaId();
        UUID correlationId = UUID.randomUUID();
        UUID efetivacaoId = UUID.randomUUID();
        Timestamp agora = Timestamp.from(Instant.now());

        try (Connection conApp = DriverManager.getConnection(creditoJdbcUrl, APP_USER, APP_PASSWORD)) {
            executar(conApp, """
                            insert into solicitacao_aumento_limite
                                (id, cliente_id, conta_id, originador_id, origem_solicitacao, canal_manifestacao,
                                 status, correlation_id, registrada_em, atualizada_em)
                            values (?, '1', ?, 'gerente.priv', 'CLIENTE', 'PRESENCIAL', 'SOLICITADA', ?, ?, ?)
                            """,
                    id, contaPriv.valor(), correlationId, agora, agora);

            executar(conApp, """
                            insert into contexto_decisao_credito
                                (solicitacao_id, limite_cheque_especial_vigente, situacao_conta,
                                 classificacao_risco_credito_base, limite_solicitado, incremento_solicitado,
                                 versao_politica_credito, capturado_em, dados_credito_core_fonte,
                                 dados_credito_core_consultado_em)
                            values (?, 500000, 'REGULAR', 'BAIXO', 600000, 100000, 'v1', ?, 'CORE_LEGADO', ?)
                            """,
                    id, agora, agora);

            executar(conApp, """
                            insert into decisao_credito
                                (solicitacao_id, resultado, motivo, versao_politica_credito, decidida_em, autor_tipo, autor_id)
                            values (?, 'APROVADA', 'DENTRO_DA_POLITICA_AUTOMATICA', 'v1', ?, 'SISTEMA', 'MOTOR_DECISAO_CREDITO')
                            """,
                    id, agora);

            executar(conApp, """
                            insert into registro_idempotencia (originador_id, idempotency_key, fingerprint, solicitacao_id, criado_em)
                            values ('gerente.priv', ?, ?, ?, ?)
                            """,
                    UUID.randomUUID(), "a".repeat(64), id, agora);

            executar(conApp, """
                            insert into outbox_mensagem
                                (message_id, tipo, destino, solicitacao_id, efetivacao_id, conta_id,
                                 limite_cheque_especial_vigente_esperado, limite_solicitado, correlation_id, criado_em)
                            values (?, 'EfetivarLimite', 'CORE_LEGADO', ?, ?, ?, 500000, 600000, ?, ?)
                            """,
                    UUID.randomUUID(), id, efetivacaoId, contaPriv.valor(), correlationId, agora);

            // O critico: a PK e GENERATED ALWAYS AS IDENTITY. Sem GRANT USAGE, SELECT ON
            // SEQUENCES para credito_app (infra/postgres-init/02-credito.sh), este INSERT falharia
            // mesmo com GRANT INSERT concedido na tabela em si.
            String fatoId = "PRIV-SMOKE:" + id;
            executar(conApp, """
                            insert into historico_solicitacao (solicitacao_id, fato_id, tipo_fato, ator_tipo, ator_id, ocorrido_em)
                            values (?, ?, 'SOLICITACAO_REGISTRADA', 'HUMANO', 'gerente.priv', ?)
                            """,
                    id, fatoId, agora);

            Long idGerado = appJdbcClient.sql("select id from historico_solicitacao where fato_id = :fatoId")
                    .param("fatoId", fatoId)
                    .query(Long.class)
                    .single();
            assertThat(idGerado).isPositive();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Migrations e constraints: os CHECKs nomeados realmente existem e recusam valor invalido.
    // ---------------------------------------------------------------------------------------

    @Test
    void migration_recusaStatusForaDoEnum() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> appJdbcClient.sql("""
                                insert into solicitacao_aumento_limite
                                    (id, cliente_id, conta_id, originador_id, origem_solicitacao, canal_manifestacao,
                                     status, correlation_id, registrada_em, atualizada_em)
                                values (:id, '1', :contaId, 'gerente.teste', 'CLIENTE', 'PRESENCIAL', 'BOGUS', :correlationId, now(), now())
                                """)
                        .param("id", id)
                        .param("contaId", novaContaId().valor())
                        .param("correlationId", UUID.randomUUID())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void migration_recusaObservacaoVaziaNaoNula() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> appJdbcClient.sql("""
                                insert into solicitacao_aumento_limite
                                    (id, cliente_id, conta_id, originador_id, origem_solicitacao, canal_manifestacao,
                                     observacao, status, correlation_id, registrada_em, atualizada_em)
                                values (:id, '1', :contaId, 'gerente.teste', 'CLIENTE', 'PRESENCIAL', '', 'SOLICITADA', :correlationId, now(), now())
                                """)
                        .param("id", id)
                        .param("contaId", novaContaId().valor())
                        .param("correlationId", UUID.randomUUID())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------------------------------
    // Caminho feliz: registrar + carregarParaDecisao (AC32, procedencia congelada).
    // ---------------------------------------------------------------------------------------

    @Test
    void registrar_criaSolicitacaoComContextoCompleto_procedenciaCongeladaSobreviveAReleitura() {
        ContaId contaId = novaContaId();
        NovaSolicitacaoAumentoLimite comando = novaSolicitacao(contaId, novaIdempotencyKey(), "fp-happy-path", 500_000L, 600_000L);

        ResultadoRegistroSolicitacao resultado = adapter.registrar(comando);
        assertThat(resultado).isInstanceOf(SolicitacaoCriada.class);
        SolicitacaoId id = ((SolicitacaoCriada) resultado).id();

        CargaParaDecisao carga = adapter.carregarParaDecisao(id);
        assertThat(carga.status()).isEqualTo(StatusSolicitacaoAumentoLimite.SOLICITADA);
        assertThat(carga.contaId()).isEqualTo(contaId);
        assertThat(carga.correlationId()).isEqualTo(comando.correlationId());
        assertThat(carga.contexto().limiteSolicitado().centavos()).isEqualTo(600_000L);
        assertThat(carga.contexto().incrementoSolicitado().centavos()).isEqualTo(100_000L);
        assertThat(carga.contexto().versaoPoliticaCredito()).isEqualTo(new VersaoPoliticaCredito("v1"));
        assertThat(carga.contexto().dadosCreditoCore().limiteChequeEspecialVigente().centavos()).isEqualTo(500_000L);
        assertThat(carga.contexto().dadosCreditoCore().situacaoConta()).isEqualTo(SituacaoConta.REGULAR);
        assertThat(carga.contexto().dadosCreditoCore().classificacaoRiscoCreditoBase()).isEqualTo(ClassificacaoRiscoCreditoBase.BAIXO);
        assertThat(carga.contexto().dadosCreditoCore().fonte()).isEqualTo("CORE_LEGADO");
        assertThat(carga.contexto().dadosCreditoCore().consultadoEm())
                .isEqualTo(comando.contextoDecisaoCredito().dadosCreditoCore().consultadoEm());
        // AC32: nada aqui reconsulta ou recalcula a partir de uma fonte externa -- e uma releitura
        // pura do que TX1 gravou.
    }

    @Test
    void carregarParaDecisao_idInexistente_lancaSolicitacaoNaoEncontrada() {
        assertThatThrownBy(() -> adapter.carregarParaDecisao(new SolicitacaoId(UUID.randomUUID())))
                .isInstanceOf(SolicitacaoNaoEncontradaException.class);
    }

    @Test
    void aplicarDecisao_idInexistente_lancaSolicitacaoNaoEncontrada() {
        SolicitacaoId idInexistente = new SolicitacaoId(UUID.randomUUID());
        Instant agora = Instant.now();
        assertThatThrownBy(() -> adapter.aplicarDecisao(
                idInexistente, decisaoRejeitada(agora), null, entradaDecisao(idInexistente, agora)))
                .isInstanceOf(SolicitacaoNaoEncontradaException.class);
    }

    // ---------------------------------------------------------------------------------------
    // Atomicidade de TX2.
    // ---------------------------------------------------------------------------------------

    @Test
    void aplicarDecisao_aprovada_gravaDecisaoStatusEfetivacaoIdEUmaLinhaDeOutbox_atomicamente() {
        ContaId contaId = novaContaId();
        SolicitacaoId id = registrarNova(contaId, novaIdempotencyKey(), "fp-aprovada");
        CorrelationId correlationId = adapter.carregarParaDecisao(id).correlationId();
        Instant decididaEm = Instant.parse("2026-09-02T11:00:00Z");
        IntencaoEfetivacao intencao = intencaoPara(contaId, correlationId);

        ResultadoAplicacaoDecisao resultado =
                adapter.aplicarDecisao(id, decisaoAprovada(decididaEm), intencao, entradaDecisao(id, decididaEm));

        assertThat(resultado.decidiuAgora()).isTrue();
        assertThat(resultado.statusResultante()).isEqualTo(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        assertThat(contarPor("decisao_credito", "solicitacao_id", id.valor())).isEqualTo(1L);

        String statusPersistido = appJdbcClient.sql("select status from solicitacao_aumento_limite where id = :id")
                .param("id", id.valor()).query(String.class).single();
        assertThat(statusPersistido).isEqualTo("AGUARDANDO_EFETIVACAO");

        UUID efetivacaoIdPersistido = appJdbcClient.sql("select efetivacao_id from solicitacao_aumento_limite where id = :id")
                .param("id", id.valor()).query(UUID.class).single();
        assertThat(efetivacaoIdPersistido).isEqualTo(intencao.efetivacaoId().valor());

        assertThat(contarPor("outbox_mensagem", "efetivacao_id", intencao.efetivacaoId().valor())).isEqualTo(1L);
    }

    @Test
    void aplicarDecisao_rejeitada_gravaDecisaoStatusRejeitadaSemEfetivacaoIdESemOutbox() {
        ContaId contaId = novaContaId();
        SolicitacaoId id = registrarNova(contaId, novaIdempotencyKey(), "fp-rejeitada");
        Instant decididaEm = Instant.parse("2026-09-02T11:05:00Z");

        ResultadoAplicacaoDecisao resultado =
                adapter.aplicarDecisao(id, decisaoRejeitada(decididaEm), null, entradaDecisao(id, decididaEm));

        assertThat(resultado.decidiuAgora()).isTrue();
        assertThat(resultado.statusResultante()).isEqualTo(StatusSolicitacaoAumentoLimite.REJEITADA);
        assertThat(contarPor("decisao_credito", "solicitacao_id", id.valor())).isEqualTo(1L);

        // .optional() (nao .single()): efetivacao_id e NULL aqui, e MappedQuerySpec.single() exige
        // um valor nao-nulo -- e exatamente essa distincao ("linha existe, coluna e NULL" vs
        // "nenhuma linha") que faz optional() o metodo certo para uma coluna nullable.
        UUID efetivacaoIdPersistido = appJdbcClient.sql("select efetivacao_id from solicitacao_aumento_limite where id = :id")
                .param("id", id.valor()).query(UUID.class).optional().orElse(null);
        assertThat(efetivacaoIdPersistido).isNull();

        assertThat(contarPor("outbox_mensagem", "solicitacao_id", id.valor())).isEqualTo(0L);
    }

    @Test
    void aplicarDecisao_chamadoDuasVezesSobreSolicitacaoJaDecidida_naoReescreveNadaEHistoricoNaoCresce() {
        ContaId contaId = novaContaId();
        SolicitacaoId id = registrarNova(contaId, novaIdempotencyKey(), "fp-dedup");
        Instant decididaEm = Instant.parse("2026-09-02T11:10:00Z");

        ResultadoAplicacaoDecisao primeira =
                adapter.aplicarDecisao(id, decisaoRejeitada(decididaEm), null, entradaDecisao(id, decididaEm));
        assertThat(primeira.decidiuAgora()).isTrue();

        long historicoAntes = contarPor("historico_solicitacao", "solicitacao_id", id.valor());

        ResultadoAplicacaoDecisao segunda =
                adapter.aplicarDecisao(id, decisaoRejeitada(decididaEm), null, entradaDecisao(id, decididaEm));
        assertThat(segunda.decidiuAgora()).isFalse();
        assertThat(segunda.statusResultante()).isEqualTo(StatusSolicitacaoAumentoLimite.REJEITADA);
        assertThat(segunda.decisaoVigente().motivo()).isEqualTo(primeira.decisaoVigente().motivo());

        long historicoDepois = contarPor("historico_solicitacao", "solicitacao_id", id.valor());
        assertThat(historicoDepois).isEqualTo(historicoAntes);
        assertThat(contarPor("decisao_credito", "solicitacao_id", id.valor())).isEqualTo(1L);
    }

    // ---------------------------------------------------------------------------------------
    // AC18 -- retomada entre TX1 e TX2.
    // ---------------------------------------------------------------------------------------

    @Test
    void registrarEDecidirEmDoisPassosSeparados_simulandoInterrupcaoEntreTx1ETx2_produzResultadoIdenticoAoCaminhoSemInterrupcao() {
        ContaId contaId = novaContaId();
        NovaSolicitacaoAumentoLimite comando = novaSolicitacao(contaId, novaIdempotencyKey(), "fp-ac18", 500_000L, 600_000L);

        // "Interrupcao": so chama registrar (TX1) e para -- exatamente como um processo que morre
        // entre as duas fases (spec, secao "Fronteira transacional da submissao").
        SolicitacaoId id = ((SolicitacaoCriada) adapter.registrar(comando)).id();

        CargaParaDecisao carga = adapter.carregarParaDecisao(id);
        assertThat(carga.status()).isEqualTo(StatusSolicitacaoAumentoLimite.SOLICITADA);
        assertThat(carga.contexto().versaoPoliticaCredito()).isEqualTo(new VersaoPoliticaCredito("v1"));

        // "Retomada": so agora TX2 acontece, como se fosse outra requisicao/processo, usando
        // exclusivamente o contexto ja persistido -- nenhuma nova consulta remota acontece aqui
        // (nem poderia: este adapter nao tem DireitoDeAtendimentoPort nem DadosCreditoCorePort).
        Instant decididaEm = Instant.parse("2026-09-02T12:00:00Z");
        ResultadoAplicacaoDecisao resultado = adapter.aplicarDecisao(
                id, decisaoAprovada(decididaEm), intencaoPara(contaId, carga.correlationId()), entradaDecisao(id, decididaEm));

        assertThat(resultado.decidiuAgora()).isTrue();
        assertThat(resultado.statusResultante()).isEqualTo(StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO);
        assertThat(resultado.decisaoVigente().resultado()).isEqualTo(ResultadoDecisaoCredito.APROVADA);
        // aplicarDecisao e puramente mecanico: o resultado nao depende de quanto tempo passou nem
        // de quantas leituras aconteceram entre TX1 e TX2 -- e identico ao caminho sem interrupcao.
    }

    // ---------------------------------------------------------------------------------------
    // AC8, AC9, AC31.
    // ---------------------------------------------------------------------------------------

    @Test
    void registrar_duasVezesComMesmaKeyEMesmoFingerprint_replayNaoCriaLinhasNovasEmNenhumaTabela() {
        ContaId contaId = novaContaId();
        IdempotencyKey key = novaIdempotencyKey();
        NovaSolicitacaoAumentoLimite comando = novaSolicitacao(contaId, key, "fp-replay");

        ResultadoRegistroSolicitacao primeira = adapter.registrar(comando);
        assertThat(primeira).isInstanceOf(SolicitacaoCriada.class);
        SolicitacaoId id = ((SolicitacaoCriada) primeira).id();

        Map<String, Long> antes = contarTodasAsTabelas();

        ResultadoRegistroSolicitacao segunda = adapter.registrar(comando);
        assertThat(segunda).isInstanceOfSatisfying(RegistroIdempotenteEncontrado.class,
                r -> assertThat(r.registro().solicitacaoId()).isEqualTo(id));

        assertThat(contarTodasAsTabelas()).isEqualTo(antes);
    }

    @Test
    void registrar_mesmaKeyComFingerprintDivergente_devolveRegistroOriginalInalterado() {
        IdempotencyKey key = novaIdempotencyKey();
        NovaSolicitacaoAumentoLimite original = novaSolicitacao(novaContaId(), key, "fp-original");
        SolicitacaoId idOriginal = ((SolicitacaoCriada) adapter.registrar(original)).id();

        // Conta diferente (e portanto fingerprint necessariamente diferente): a este adapter nao
        // cabe decidir 409 vs 422 (isso e ClassificadorIdempotencia, na aplicacao) -- so precisa
        // devolver o registro original intacto.
        NovaSolicitacaoAumentoLimite comFingerprintDiferente = novaSolicitacao(novaContaId(), key, "fp-divergente");
        ResultadoRegistroSolicitacao resultado = adapter.registrar(comFingerprintDiferente);

        assertThat(resultado).isInstanceOfSatisfying(RegistroIdempotenteEncontrado.class, r -> {
            assertThat(r.registro().solicitacaoId()).isEqualTo(idOriginal);
            assertThat(r.registro().fingerprint()).isEqualTo(fingerprintDeTeste("fp-original"));
        });
    }

    @Test
    void registrar_apostSolicitacaoTerminalNaMesmaConta_permiteNovaSolicitacaoComKeyNova() {
        ContaId contaId = novaContaId();
        SolicitacaoId primeiraId = registrarNova(contaId, novaIdempotencyKey(), "fp-1");
        Instant decididaEm = Instant.parse("2026-09-02T11:20:00Z");
        adapter.aplicarDecisao(primeiraId, decisaoRejeitada(decididaEm), null, entradaDecisao(primeiraId, decididaEm));

        ResultadoRegistroSolicitacao resultado = adapter.registrar(novaSolicitacao(contaId, novaIdempotencyKey(), "fp-2"));

        assertThat(resultado).isInstanceOf(SolicitacaoCriada.class);
        assertThat(contarPor("solicitacao_aumento_limite", "conta_id", contaId.valor())).isEqualTo(2L);
    }

    // ---------------------------------------------------------------------------------------
    // AC10 + as tres corridas do guardrail de concorrencia (plano #0003, secao 3).
    // ---------------------------------------------------------------------------------------

    @Test
    void concorrencia_mesmaContaKeysDiferentes_apenasUmaCriaAOutraRecebeNaoTerminalExistente() throws Exception {
        ContaId contaId = novaContaId();
        NovaSolicitacaoAumentoLimite comandoA = novaSolicitacao(contaId, novaIdempotencyKey(), "fp-conc-a");
        NovaSolicitacaoAumentoLimite comandoB = novaSolicitacao(contaId, novaIdempotencyKey(), "fp-conc-b");

        List<ResultadoRegistroSolicitacao> resultados = executarConcorrentemente(
                () -> adapter.registrar(comandoA),
                () -> adapter.registrar(comandoB));

        assertThat(resultados.stream().filter(SolicitacaoCriada.class::isInstance).count()).isEqualTo(1L);
        assertThat(resultados.stream().filter(SolicitacaoNaoTerminalExistente.class::isInstance).count()).isEqualTo(1L);
        assertThat(contarPor("solicitacao_aumento_limite", "conta_id", contaId.valor())).isEqualTo(1L);
    }

    /**
     * A aspercao mais importante desta etapa (plano #0003, guardrail de concorrencia): a
     * perdedora bate no indice de nao-terminal-por-conta ANTES de chegar na PK de idempotencia,
     * mas ainda assim precisa ser classificada como idempotencia -- NUNCA como uma segunda
     * solicitacao concorrente para a mesma conta.
     */
    @Test
    void concorrencia_mesmaKeyMesmoFingerprintMesmaConta_umaCriaAOutraRecebeRegistroIdempotenteComMesmaSolicitacaoId() throws Exception {
        ContaId contaId = novaContaId();
        NovaSolicitacaoAumentoLimite comando = novaSolicitacao(contaId, novaIdempotencyKey(), "fp-conc-mesma");

        List<ResultadoRegistroSolicitacao> resultados = executarConcorrentemente(
                () -> adapter.registrar(comando),
                () -> adapter.registrar(comando));

        assertThat(resultados).noneMatch(SolicitacaoNaoTerminalExistente.class::isInstance);

        SolicitacaoId idCriada = resultados.stream()
                .filter(SolicitacaoCriada.class::isInstance)
                .map(r -> ((SolicitacaoCriada) r).id())
                .findFirst()
                .orElseThrow(() -> new AssertionError("nenhum dos dois resultados foi SolicitacaoCriada: " + resultados));

        List<SolicitacaoId> idsEncontrados = resultados.stream()
                .filter(RegistroIdempotenteEncontrado.class::isInstance)
                .map(r -> ((RegistroIdempotenteEncontrado) r).registro().solicitacaoId())
                .toList();

        assertThat(idsEncontrados).containsExactly(idCriada);
        assertThat(contarPor("solicitacao_aumento_limite", "conta_id", contaId.valor())).isEqualTo(1L);
    }

    @Test
    void concorrencia_mesmaKeyContasDiferentes_perdedoraRecebeRegistroComFingerprintDoVencedor() throws Exception {
        IdempotencyKey key = novaIdempotencyKey();
        ContaId contaA = novaContaId();
        ContaId contaB = novaContaId();
        NovaSolicitacaoAumentoLimite comandoA = novaSolicitacao(contaA, key, "fp-conta-a");
        NovaSolicitacaoAumentoLimite comandoB = novaSolicitacao(contaB, key, "fp-conta-b");

        List<ResultadoRegistroSolicitacao> resultados = executarConcorrentemente(
                () -> adapter.registrar(comandoA),
                () -> adapter.registrar(comandoB));

        assertThat(resultados).noneMatch(SolicitacaoNaoTerminalExistente.class::isInstance);
        assertThat(resultados.stream().filter(SolicitacaoCriada.class::isInstance).count()).isEqualTo(1L);
        assertThat(resultados.stream().filter(RegistroIdempotenteEncontrado.class::isInstance).count()).isEqualTo(1L);

        // A perdedora sofre rollback INTEIRO da propria tentativa (o INSERT #1 e #2 dela tambem
        // sao desfeitos): o total de solicitacoes entre as duas contas nunca pode ser 2.
        long totalSolicitacoes = contarPor("solicitacao_aumento_limite", "conta_id", contaA.valor())
                + contarPor("solicitacao_aumento_limite", "conta_id", contaB.valor());
        assertThat(totalSolicitacoes).isEqualTo(1L);

        RegistroIdempotenteEncontrado encontrado = (RegistroIdempotenteEncontrado) resultados.stream()
                .filter(RegistroIdempotenteEncontrado.class::isInstance).findFirst().orElseThrow();
        // O fingerprint devolvido e o do VENCEDOR -- decidir 409 (mesma conta) nunca se aplica
        // aqui (contas diferentes); classificar 409 vs 422 fica a cargo da aplicacao.
        assertThat(encontrado.registro().fingerprint())
                .isIn(fingerprintDeTeste("fp-conta-a"), fingerprintDeTeste("fp-conta-b"));
    }

    // ---------------------------------------------------------------------------------------
    // FOR UPDATE NOWAIT sob concorrencia real.
    // ---------------------------------------------------------------------------------------

    /**
     * Duas transacoes reais disputando o lock exclusivo de {@code aplicarDecisao} sobre a MESMA
     * solicitacao. Para que a segunda genuinamente encontre o lock ocupado (e nao apenas vença uma
     * corrida de sorte), a primeira roda atraves de um {@link DataSource} decorado que, de forma
     * ortogonal ao codigo de producao, insere um pequeno atraso IMEDIATAMENTE apos o
     * {@code SELECT ... FOR UPDATE NOWAIT} ter adquirido o lock -- e so libera a segunda thread
     * (via {@link CountDownLatch}) depois de confirmar que o lock ja foi de fato adquirido.
     */
    @Test
    void aplicarDecisao_forUpdateNowaitSobConcorrenciaReal_umaGanhaAOutraRecebeIdempotenciaEmProcessamento() throws Exception {
        ContaId contaId = novaContaId();
        SolicitacaoId id = registrarNova(contaId, novaIdempotencyKey(), "fp-nowait");
        CorrelationId correlationId = adapter.carregarParaDecisao(id).correlationId();
        Instant decididaEm = Instant.parse("2026-09-02T13:00:00Z");

        CountDownLatch lockAdquirido = new CountDownLatch(1);
        DataSource dataSourceLenta = comAtrasoAposForUpdateNowait(appDataSource, lockAdquirido, 500);
        var adapterLento = new PostgresSolicitacoesAumentoLimiteAdapter(
                JdbcClient.create(dataSourceLenta), new DataSourceTransactionManager(dataSourceLenta), registroIdempotenciaAdapter);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResultadoAplicacaoDecisao> futuroLento = executor.submit(() -> adapterLento.aplicarDecisao(
                    id, decisaoAprovada(decididaEm), intencaoPara(contaId, correlationId), entradaDecisao(id, decididaEm)));

            // So tenta a segunda depois de confirmar que a primeira ja possui o lock -- sem isso
            // o teste seria uma corrida sem garantia de encontrar o lock ocupado.
            assertThat(lockAdquirido.await(5, TimeUnit.SECONDS)).isTrue();

            Future<ResultadoAplicacaoDecisao> futuroRapido = executor.submit(() -> adapter.aplicarDecisao(
                    id, decisaoAprovada(decididaEm), intencaoPara(contaId, correlationId), entradaDecisao(id, decididaEm)));

            assertThatThrownBy(futuroRapido::get).hasCauseInstanceOf(IdempotenciaEmProcessamentoException.class);

            ResultadoAplicacaoDecisao resultadoLento = futuroLento.get(10, TimeUnit.SECONDS);
            assertThat(resultadoLento.decidiuAgora()).isTrue();
        } finally {
            executor.shutdown();
        }

        assertThat(contarPor("decisao_credito", "solicitacao_id", id.valor())).isEqualTo(1L);
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures e utilitarios de teste.
    // ---------------------------------------------------------------------------------------

    private static synchronized ContaId novaContaId() {
        contaSequencial++;
        return new ContaId(Long.toString(contaSequencial));
    }

    private static IdempotencyKey novaIdempotencyKey() {
        return new IdempotencyKey(UUID.randomUUID());
    }

    private static NovaSolicitacaoAumentoLimite novaSolicitacao(ContaId contaId, IdempotencyKey key, String fingerprint) {
        return novaSolicitacao(contaId, key, fingerprint, 500_000L, 600_000L);
    }

    private static NovaSolicitacaoAumentoLimite novaSolicitacao(
            ContaId contaId, IdempotencyKey key, String fingerprint, long limiteVigenteCentavos, long limiteSolicitadoCentavos) {
        Instant agora = Instant.parse("2026-09-02T10:00:00Z");
        DadosCreditoCore dadosCore = new DadosCreditoCore(
                new LimiteChequeEspecialVigente(limiteVigenteCentavos),
                SituacaoConta.REGULAR,
                ClassificacaoRiscoCreditoBase.BAIXO,
                agora,
                "CORE_LEGADO");
        ContextoDecisaoCredito contexto = ContextoDecisaoCredito.congelar(
                dadosCore, new LimiteSolicitado(limiteSolicitadoCentavos), new VersaoPoliticaCredito("v1"), agora);
        return new NovaSolicitacaoAumentoLimite(
                new ClienteId("42"),
                contaId,
                new AtorId("gerente.teste"),
                OrigemSolicitacao.CLIENTE,
                new ManifestacaoCliente(CanalManifestacao.PRESENCIAL, "observacao de teste"),
                contexto,
                new CorrelationId(UUID.randomUUID()),
                key,
                fingerprintDeTeste(fingerprint),
                agora);
    }

    /**
     * {@code registro_idempotencia.fingerprint} e {@code CHAR(64)} (a migration espelha o formato
     * real de {@code FingerprintCanonico}, sempre um SHA-256 hex de 64 caracteres) -- Postgres
     * preenche com espacos a direita qualquer valor mais curto gravado num {@code CHAR(n)}, e
     * devolve o valor JA preenchido na leitura. Fixtures de teste usam rotulos curtos e legiveis
     * ("fp-original"); esta funcao os normaliza para exatamente 64 caracteres ANTES de persistir,
     * para que comparacoes de igualdade no teste batam com o que a releitura do banco devolve.
     */
    private static String fingerprintDeTeste(String rotulo) {
        if (rotulo.length() >= 64) {
            return rotulo.substring(0, 64);
        }
        return rotulo + "0".repeat(64 - rotulo.length());
    }

    private SolicitacaoId registrarNova(ContaId contaId, IdempotencyKey key, String fingerprint) {
        ResultadoRegistroSolicitacao resultado = adapter.registrar(novaSolicitacao(contaId, key, fingerprint));
        assertThat(resultado).isInstanceOf(SolicitacaoCriada.class);
        return ((SolicitacaoCriada) resultado).id();
    }

    private static DecisaoCredito decisaoAprovada(Instant decididaEm) {
        return new DecisaoCredito(
                ResultadoDecisaoCredito.APROVADA, MotivoDecisaoCredito.DENTRO_DA_POLITICA_AUTOMATICA,
                new VersaoPoliticaCredito("v1"), decididaEm, AtorSistema.MOTOR_DECISAO_CREDITO);
    }

    private static DecisaoCredito decisaoRejeitada(Instant decididaEm) {
        return new DecisaoCredito(
                ResultadoDecisaoCredito.REJEITADA, MotivoDecisaoCredito.CONTA_NAO_ELEGIVEL,
                new VersaoPoliticaCredito("v1"), decididaEm, AtorSistema.MOTOR_DECISAO_CREDITO);
    }

    private static IntencaoEfetivacao intencaoPara(ContaId contaId, CorrelationId correlationId) {
        return new IntencaoEfetivacao(
                new EfetivacaoId(UUID.randomUUID()), UUID.randomUUID(), contaId,
                new LimiteChequeEspecialVigente(500_000L), new LimiteSolicitado(600_000L), correlationId);
    }

    private static EntradaHistorico entradaDecisao(SolicitacaoId id, Instant ocorridoEm) {
        return new EntradaHistorico(
                "DECISAO:" + id.valor(), TipoFatoHistorico.DECISAO_AUTOMATICA_REGISTRADA,
                AtorSistema.MOTOR_DECISAO_CREDITO, ocorridoEm);
    }

    private Long contarPor(String tabela, String coluna, Object valor) {
        return appJdbcClient.sql("select count(*) from " + tabela + " where " + coluna + " = :valor")
                .param("valor", valor)
                .query(Long.class)
                .single();
    }

    private Map<String, Long> contarTodasAsTabelas() {
        Map<String, Long> contagens = new LinkedHashMap<>();
        for (String tabela : TODAS_AS_TABELAS) {
            contagens.put(tabela, appJdbcClient.sql("select count(*) from " + tabela).query(Long.class).single());
        }
        return contagens;
    }

    private static void executar(Connection con, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.execute();
        }
    }

    @SafeVarargs
    private static <T> List<T> executarConcorrentemente(Callable<T>... tarefas) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tarefas.length);
        try {
            CyclicBarrier barreira = new CyclicBarrier(tarefas.length);
            List<Future<T>> futuros = new ArrayList<>();
            for (Callable<T> tarefa : tarefas) {
                futuros.add(executor.submit(() -> {
                    barreira.await(10, TimeUnit.SECONDS);
                    return tarefa.call();
                }));
            }
            List<T> resultados = new ArrayList<>();
            for (Future<T> futuro : futuros) {
                resultados.add(futuro.get(10, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Decorator de {@link DataSource} usado SOMENTE por
     * {@code aplicarDecisao_forUpdateNowaitSobConcorrenciaReal_...}: intercepta a conexao que o
     * Spring obtem para abrir a transacao e, quando detecta o {@code PreparedStatement} do
     * {@code SELECT ... FOR UPDATE NOWAIT}, conta {@code aoAdquirirLock} e so entao dorme --
     * mantendo a linha bloqueada por {@code atrasoMillis} antes da proxima instrucao da mesma
     * transacao. Nao altera nenhum codigo de producao.
     */
    private static DataSource comAtrasoAposForUpdateNowait(DataSource real, CountDownLatch aoAdquirirLock, long atrasoMillis) {
        InvocationHandler handler = (proxy, method, args) -> {
            try {
                Object resultado = method.invoke(real, args);
                if ("getConnection".equals(method.getName()) && resultado instanceof Connection conexaoReal) {
                    return proxyConexaoComAtraso(conexaoReal, aoAdquirirLock, atrasoMillis);
                }
                return resultado;
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, handler);
    }

    private static Connection proxyConexaoComAtraso(Connection real, CountDownLatch aoAdquirirLock, long atrasoMillis) {
        InvocationHandler handler = (proxy, method, args) -> {
            try {
                Object resultado = method.invoke(real, args);
                if ("prepareStatement".equals(method.getName()) && args != null && args.length > 0
                        && args[0] instanceof String sql
                        && sql.toLowerCase(Locale.ROOT).contains("for update nowait")) {
                    return proxyStatementComAtraso((PreparedStatement) resultado, aoAdquirirLock, atrasoMillis);
                }
                return resultado;
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
    }

    private static PreparedStatement proxyStatementComAtraso(PreparedStatement real, CountDownLatch aoAdquirirLock, long atrasoMillis) {
        InvocationHandler handler = (proxy, method, args) -> {
            try {
                Object resultado = method.invoke(real, args);
                if ("executeQuery".equals(method.getName())) {
                    aoAdquirirLock.countDown();
                    Thread.sleep(atrasoMillis);
                }
                return resultado;
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, handler);
    }
}
