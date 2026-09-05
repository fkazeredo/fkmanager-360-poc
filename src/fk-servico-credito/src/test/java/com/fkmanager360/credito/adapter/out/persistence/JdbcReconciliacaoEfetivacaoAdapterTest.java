package com.fkmanager360.credito.adapter.out.persistence;

import com.fkmanager360.credito.application.ResultadoCicloReconciliacao;
import com.fkmanager360.credito.application.PoliticaRetryEntrega;
import com.fkmanager360.credito.application.port.out.AlertaOperacionalPort;
import com.fkmanager360.credito.application.port.out.ConsultaStatusEfetivacaoCorePort;
import com.fkmanager360.credito.application.port.out.EfetivacaoReconciliacaoReclamada;
import com.fkmanager360.credito.application.port.out.EntregasEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ReclamacaoReconciliacao;
import com.fkmanager360.credito.application.port.out.ReconciliacaoEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ResultadoConsultaStatusCore;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoRecebido;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroEfetivacao;
import com.fkmanager360.credito.application.port.out.TransacaoPort;
import com.fkmanager360.credito.application.usecase.ReconciliarEfetivacoes;
import com.fkmanager360.credito.application.usecase.RegistrarResultadoEfetivacao;
import com.fkmanager360.credito.adapter.out.persistence.repository.SolicitacaoAumentoLimiteRepository;
import com.fkmanager360.credito.domain.AtorSistema;
import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.MotivoFalhaEfetivacao;
import com.fkmanager360.credito.domain.ProtocoloCore;
import com.fkmanager360.credito.domain.SolicitacaoId;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3 (ADR-0018): claim/fencing/agenda de {@code reconciliacao_efetivacao} contra PostgreSQL real
 * via Testcontainers, aplicando as migrations reais (V1+V2+V3). Mesmo padrao de
 * {@code JdbcEntregasEfetivacaoAdapterTest} (#0004): container proprio, papeis
 * {@code credito_migrator}/{@code credito_app} recriados aqui, adapters obtidos pelo TIPO DA PORT a
 * partir de um contexto Spring minimo montado sobre a credencial de app.
 *
 * <p><b>TX-B provada com {@link ReconciliarEfetivacoes} REAL</b>, nao uma recomposicao manual no
 * teste: os adapters de persistencia ({@link ReconciliacaoEfetivacaoPort},
 * {@link ResultadoEfetivacaoPort}, {@link TransacaoPort}) sao os de producao, vindos do contexto
 * Spring; so {@link ConsultaStatusEfetivacaoCorePort} (HTTP) e {@link AlertaOperacionalPort} (log)
 * sao fakes -- exatamente a fronteira que ADR-0018 delimita para S3 (persistencia contra o real,
 * nunca contra simulacro; a ACL em si e provada em S4).
 */
@Testcontainers
class JdbcReconciliacaoEfetivacaoAdapterTest {

    private static final String MIGRATOR_USER = "credito_migrator";
    private static final String MIGRATOR_PASSWORD = "migrator-teste-nao-usar-em-producao";
    private static final String APP_USER = "credito_app";
    private static final String APP_PASSWORD = "app-teste-nao-usar-em-producao";

    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration BACKOFF_LONGO = Duration.ofMinutes(5);
    private static final Instant AGORA = Instant.parse("2026-09-05T10:00:00Z");
    private static final Duration JANELA = Duration.ofMinutes(10);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static String creditoJdbcUrl;
    private static HikariDataSource appDataSource;
    private static JdbcClient appJdbcClient;
    private static AnnotationConfigApplicationContext applicationContext;
    private static ReconciliacaoEfetivacaoPort adapter;
    private static RegistrarResultadoEfetivacao registrarResultadoEfetivacao;
    private static TransacaoPort transacaoPort;

    private static long contaSequencial = 9_300_000_000L;

    @BeforeAll
    static void provisionarPapeisMigrarESubirAdapterComCredencialDeApp() throws Exception {
        String enderecoContainer = enderecoNaRedeBridge(POSTGRES);
        String superuserJdbcUrl = "jdbc:postgresql://" + enderecoContainer + ":5432/" + POSTGRES.getDatabaseName();
        creditoJdbcUrl = "jdbc:postgresql://" + enderecoContainer + ":5432/credito_db";

        try (Connection superusuario = conectarComRetry(superuserJdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
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

        Flyway.configure()
                .dataSource(creditoJdbcUrl, MIGRATOR_USER, MIGRATOR_PASSWORD)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(creditoJdbcUrl);
        config.setUsername(APP_USER);
        config.setPassword(APP_PASSWORD);
        config.setMaximumPoolSize(10);
        appDataSource = new HikariDataSource(config);
        appJdbcClient = JdbcClient.create(appDataSource);

        applicationContext = construirContexto(appDataSource);
        adapter = applicationContext.getBean(ReconciliacaoEfetivacaoPort.class);
        registrarResultadoEfetivacao = applicationContext.getBean(RegistrarResultadoEfetivacao.class);
        transacaoPort = applicationContext.getBean(TransacaoPort.class);
    }

    @AfterAll
    static void fecharRecursos() {
        applicationContext.close();
        appDataSource.close();
    }

    @AfterEach
    void limparTabelas() {
        appJdbcClient.sql("delete from reconciliacao_efetivacao").update();
        appJdbcClient.sql("delete from outbox_entrega").update();
        appJdbcClient.sql("delete from historico_solicitacao").update();
        appJdbcClient.sql("delete from outbox_mensagem").update();
        appJdbcClient.sql("delete from decisao_credito").update();
        appJdbcClient.sql("delete from contexto_decisao_credito").update();
        appJdbcClient.sql("delete from solicitacao_aumento_limite").update();
    }

    private static Connection conectarComRetry(String url, String user, String password) throws Exception {
        SQLException ultimaFalha = null;
        for (int tentativa = 0; tentativa < 20; tentativa++) {
            try {
                return DriverManager.getConnection(url, user, password);
            } catch (SQLException e) {
                ultimaFalha = e;
                Thread.sleep(500);
            }
        }
        throw ultimaFalha;
    }

    private static String enderecoNaRedeBridge(org.testcontainers.containers.GenericContainer<?> container) {
        return container.getContainerInfo().getNetworkSettings().getNetworks().get("bridge").getIpAddress();
    }

    private static AnnotationConfigApplicationContext construirContexto(DataSource dataSource) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getBeanFactory().registerSingleton("dataSource", dataSource);
        ctx.register(JpaTestConfig.class);
        ctx.refresh();
        return ctx;
    }

    @org.springframework.context.annotation.Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = SolicitacaoAumentoLimiteRepository.class)
    @ComponentScan(basePackageClasses = JdbcReconciliacaoEfetivacaoAdapter.class)
    static class JpaTestConfig {

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
            emf.setDataSource(dataSource);
            emf.setPackagesToScan("com.fkmanager360.credito.adapter.out.persistence.entity");
            emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            emf.setJpaPropertyMap(Map.of(AvailableSettings.HBM2DDL_AUTO, "validate"));
            return emf;
        }

        @Bean
        JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        RegistrarResultadoEfetivacao registrarResultadoEfetivacao(
                ResultadoEfetivacaoPort resultadoEfetivacaoPort,
                EntregasEfetivacaoPort entregasEfetivacaoPort,
                TransacaoPort transacaoPort) {
            return new RegistrarResultadoEfetivacao(resultadoEfetivacaoPort, entregasEfetivacaoPort, transacaoPort);
        }

        @Bean
        static PersistenceExceptionTranslationPostProcessor persistenceExceptionTranslationPostProcessor() {
            return new PersistenceExceptionTranslationPostProcessor();
        }

        // Mesma necessidade de JpaSolicitacoesAumentoLimiteAdapterTest/JdbcEntregasEfetivacaoAdapterTest
        // (#0006): CreditoPersistenceOperations entra pelo ComponentScan com @Value de Duration.
        @Bean
        static org.springframework.context.support.PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
            return new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();
        }

        @Bean(name = "conversionService")
        static org.springframework.core.convert.ConversionService conversionService() {
            return new org.springframework.boot.convert.ApplicationConversionService();
        }
    }

    // ---------------------------------------------------------------------------------------
    // reclamarProxima: claim unitario, fencing token, ja-terminal descartado sem HTTP.
    // ---------------------------------------------------------------------------------------

    @Test
    void reclamarProxima_semCicloPendente_devolveNenhumaPendente() {
        ReclamacaoReconciliacao reclamacao = adapter.reclamarProxima(AGORA, LEASE);

        assertThat(reclamacao).isInstanceOf(ReclamacaoReconciliacao.NenhumaPendente.class);
    }

    @Test
    void reclamarProxima_cicloDevido_geraClaimEIncrementaTentativas() throws SQLException {
        Fixture fixture = criarFixtureAguardandoEfetivacao(
                novaContaId(), 500_000, 600_000, AGORA.minusSeconds(10), AGORA.plus(JANELA));

        ReclamacaoReconciliacao reclamacao = adapter.reclamarProxima(AGORA, LEASE);

        assertThat(reclamacao).isInstanceOf(ReclamacaoReconciliacao.Reclamada.class);
        EfetivacaoReconciliacaoReclamada claim = ((ReclamacaoReconciliacao.Reclamada) reclamacao).claim();
        assertThat(claim.tentativaAtual()).isEqualTo(1);
        assertThat(claim.efetivacaoId()).isEqualTo(fixture.efetivacaoId());
        assertThat(claim.solicitacaoId()).isEqualTo(fixture.solicitacaoId());
        assertThat(claim.protocoloConhecido()).isEmpty();
        assertThat(claim.jaIndeterminada()).isFalse();

        assertThat(tentativasDe(fixture.efetivacaoId())).isEqualTo(1);
        assertThat(claimIdDe(fixture.efetivacaoId())).isEqualTo(claim.claimId());
    }

    @Test
    void reclamarProxima_protocoloJaConhecido_claimCarregaProtocolo() throws SQLException {
        Fixture fixture = criarFixtureAguardandoEfetivacao(
                novaContaId(), 500_000, 600_000, AGORA.minusSeconds(10), AGORA.plus(JANELA));
        appJdbcClient.sql("update solicitacao_aumento_limite set protocolo_core = :p where id = :id")
                .param("p", "000000000001").param("id", fixture.solicitacaoId().valor()).update();

        ReclamacaoReconciliacao reclamacao = adapter.reclamarProxima(AGORA, LEASE);

        EfetivacaoReconciliacaoReclamada claim = ((ReclamacaoReconciliacao.Reclamada) reclamacao).claim();
        assertThat(claim.protocoloConhecido()).contains(new ProtocoloCore("000000000001"));
    }

    @Test
    void reclamarProxima_cicloAindaNaoDevido_devolveNenhumaPendente() throws SQLException {
        criarFixtureAguardandoEfetivacao(novaContaId(), 500_000, 600_000, AGORA.plusSeconds(30), AGORA.plus(JANELA));

        ReclamacaoReconciliacao reclamacao = adapter.reclamarProxima(AGORA, LEASE);

        assertThat(reclamacao).isInstanceOf(ReclamacaoReconciliacao.NenhumaPendente.class);
    }

    @Test
    void reclamarProxima_duasChamadasSeguidas_naoReclamaOMesmoCicloDuasVezes() throws SQLException {
        Fixture fixture = criarFixtureAguardandoEfetivacao(
                novaContaId(), 500_000, 600_000, AGORA.minusSeconds(10), AGORA.plus(JANELA));

        adapter.reclamarProxima(AGORA, LEASE);
        ReclamacaoReconciliacao segunda = adapter.reclamarProxima(AGORA, LEASE);

        assertThat(segunda).isInstanceOf(ReclamacaoReconciliacao.NenhumaPendente.class);
        assertThat(tentativasDe(fixture.efetivacaoId())).isEqualTo(1);
    }

    @Test
    void reclamarProxima_solicitacaoJaEfetivada_terminalizaSemDevolverClaim() throws SQLException {
        Fixture fixture = criarFixtureComStatus(
                novaContaId(), "EFETIVADA", AGORA.minusSeconds(10), AGORA.plus(JANELA));

        ReclamacaoReconciliacao reclamacao = adapter.reclamarProxima(AGORA, LEASE);

        assertThat(reclamacao).isInstanceOf(ReclamacaoReconciliacao.JaTerminalDescartada.class);
        assertThat(statusReconciliacaoDe(fixture.efetivacaoId())).isEqualTo("CONCLUIDA");
    }

    /** AC34: duas instancias concorrentes do reconciliador sobre o MESMO conjunto de pendentes. */
    @Test
    void reclamarProxima_duasCiclosPendentesDuasThreadsConcorrentes_reclamaConjuntosDisjuntos() throws Exception {
        Fixture f1 = criarFixtureAguardandoEfetivacao(novaContaId(), 500_000, 600_000, AGORA.minusSeconds(10), AGORA.plus(JANELA));
        Fixture f2 = criarFixtureAguardandoEfetivacao(novaContaId(), 500_000, 600_000, AGORA.minusSeconds(10), AGORA.plus(JANELA));

        var resultados = executarConcorrentemente(
                () -> adapter.reclamarProxima(AGORA, LEASE),
                () -> adapter.reclamarProxima(AGORA, LEASE));

        var efetivacaoIdsReclamados = resultados.stream()
                .filter(r -> r instanceof ReclamacaoReconciliacao.Reclamada)
                .map(r -> ((ReclamacaoReconciliacao.Reclamada) r).claim().efetivacaoId())
                .toList();

        assertThat(efetivacaoIdsReclamados).hasSize(2).containsExactlyInAnyOrder(f1.efetivacaoId(), f2.efetivacaoId());
    }

    // ---------------------------------------------------------------------------------------
    // Fencing adversarial: claim obsoleto nunca aplica efeito.
    // ---------------------------------------------------------------------------------------

    @Test
    void claimAindaValido_claimCorrente_true_claimObsoleto_false() throws SQLException {
        criarFixtureAguardandoEfetivacao(novaContaId(), 500_000, 600_000, AGORA.minusSeconds(10), AGORA.plus(JANELA));
        EfetivacaoReconciliacaoReclamada claim = reclamar();

        assertThat(transacaoPort.executar(() -> adapter.claimAindaValido(claim))).isTrue();

        // Lease expira; outra "instancia" reclama de novo -- o claim antigo fica obsoleto.
        Instant depoisDoLeaseExpirar = AGORA.plus(LEASE).plusSeconds(1);
        adapter.reclamarProxima(depoisDoLeaseExpirar, LEASE);

        assertThat(transacaoPort.executar(() -> adapter.claimAindaValido(claim))).isFalse();
    }

    // ---------------------------------------------------------------------------------------
    // TX-B via ReconciliarEfetivacoes real: o resultado de RegistrarResultadoEfetivacao governa.
    // ---------------------------------------------------------------------------------------

    @Test
    void cicloCompleto_coreRespondeEfetivada_concluiETerminalizaReconciliacao() throws SQLException {
        Fixture fixture = criarFixtureAguardandoEfetivacao(
                novaContaId(), 500_000, 600_000, AGORA.minusSeconds(10), AGORA.plus(JANELA));

        ResultadoCicloReconciliacao ciclo = reconciliar(
                new FakeCore(new ResultadoConsultaStatusCore.Efetivada(new ProtocoloCore("000000000001"), 600_000L)),
                new FakeAlerta(), fixoEm(AGORA)).executarUmCiclo();

        assertThat(ciclo).isInstanceOf(ResultadoCicloReconciliacao.ConcluidaPorResultadoAutoritativo.class);
        assertThat(statusSolicitacaoDe(fixture.solicitacaoId())).isEqualTo("EFETIVADA");
        assertThat(statusReconciliacaoDe(fixture.efetivacaoId())).isEqualTo("CONCLUIDA");
        assertThat(claimIdDe(fixture.efetivacaoId())).isNull();
        assertThat(contarHistorico(fixture.solicitacaoId(), "RESULTADO_EFETIVACAO_REGISTRADO")).isEqualTo(1L);
    }

    @Test
    void cicloCompleto_coreRespondeFalhaDefinitiva_concluiComFalhaETerminalizaReconciliacao() throws SQLException {
        Fixture fixture = criarFixtureAguardandoEfetivacao(
                novaContaId(), 500_000, 600_000, AGORA.minusSeconds(10), AGORA.plus(JANELA));

        ResultadoCicloReconciliacao ciclo = reconciliar(
                new FakeCore(new ResultadoConsultaStatusCore.FalhaDefinitiva(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE)),
                new FakeAlerta(), fixoEm(AGORA)).executarUmCiclo();

        assertThat(ciclo).isInstanceOf(ResultadoCicloReconciliacao.ConcluidaPorResultadoAutoritativo.class);
        assertThat(statusSolicitacaoDe(fixture.solicitacaoId())).isEqualTo("FALHA_EFETIVACAO");
        assertThat(statusReconciliacaoDe(fixture.efetivacaoId())).isEqualTo("CONCLUIDA");
    }

    /** Adversarial (a) exigido pelo Owner: Efetivada com protocolo divergente -- nada conclui, reconciliacao segue PENDENTE. */
    @Test
    void cicloCompleto_coreRespondeEfetivadaComProtocoloDivergente_naoConcluiNadaEReconciliacaoPermanecePendente() throws SQLException {
        Fixture fixture = criarFixtureAguardandoEfetivacao(
                novaContaId(), 500_000, 600_000, AGORA.minusSeconds(10), AGORA.plus(JANELA));
        appJdbcClient.sql("update solicitacao_aumento_limite set protocolo_core = :p where id = :id")
                .param("p", "000000000099").param("id", fixture.solicitacaoId().valor()).update();

        ResultadoCicloReconciliacao ciclo = reconciliar(
                new FakeCore(new ResultadoConsultaStatusCore.Efetivada(new ProtocoloCore("000000000001"), 600_000L)),
                new FakeAlerta(), fixoEm(AGORA)).executarUmCiclo();

        assertThat(ciclo).isInstanceOf(ResultadoCicloReconciliacao.ReagendadaPorResultadoIncoerente.class);
        assertThat(statusSolicitacaoDe(fixture.solicitacaoId())).isEqualTo("AGUARDANDO_EFETIVACAO");
        assertThat(statusReconciliacaoDe(fixture.efetivacaoId())).isEqualTo("PENDENTE");
        assertThat(protocoloCoreDe(fixture.solicitacaoId())).isEqualTo("000000000099");
    }

    /** Adversarial (b): Efetivada com limite incoerente -- idem. */
    @Test
    void cicloCompleto_coreRespondeEfetivadaComLimiteIncoerente_naoConcluiNadaEReconciliacaoPermanecePendente() throws SQLException {
        Fixture fixture = criarFixtureAguardandoEfetivacao(
                novaContaId(), 500_000, 600_000, AGORA.minusSeconds(10), AGORA.plus(JANELA));

        ResultadoCicloReconciliacao ciclo = reconciliar(
                new FakeCore(new ResultadoConsultaStatusCore.Efetivada(new ProtocoloCore("000000000001"), 700_000L)),
                new FakeAlerta(), fixoEm(AGORA)).executarUmCiclo();

        assertThat(ciclo).isInstanceOf(ResultadoCicloReconciliacao.ReagendadaPorResultadoIncoerente.class);
        assertThat(statusSolicitacaoDe(fixture.solicitacaoId())).isEqualTo("AGUARDANDO_EFETIVACAO");
        assertThat(statusReconciliacaoDe(fixture.efetivacaoId())).isEqualTo("PENDENTE");
    }

    /**
     * Adversarial (c) exigido pelo Owner: o claim (TX-A) reclama a linha AINDA nao-terminal; um
     * callback real conclui EFETIVADA por outro caminho DEPOIS do claim (concorrencia genuina,
     * simulada aqui por uma escrita direta entre o claim e a TX-B); a TX-B, composta exatamente
     * como {@code ReconciliarEfetivacoes.aplicarResultadoAutoritativo} compoe, descobre o terminal
     * verdadeiro em {@code RegistrarResultadoEfetivacao#executar} -- nunca em TX-A. Terminal
     * permanece (nunca reescrito), a reconciliacao ainda assim conclui.
     */
    @Test
    void cicloCompleto_terminalJaExistenteEContraditorio_terminalPermaneceEReconciliacaoConclui() throws SQLException {
        Fixture fixture = criarFixtureAguardandoEfetivacao(
                novaContaId(), 500_000, 600_000, AGORA.minusSeconds(10), AGORA.plus(JANELA));
        EfetivacaoReconciliacaoReclamada claim = reclamar();

        // Concorrente: um callback real conclui EFETIVADA por outro caminho, DEPOIS do claim.
        appJdbcClient.sql("update solicitacao_aumento_limite set protocolo_core = :p, status = 'EFETIVADA' where id = :id")
                .param("p", "000000000001").param("id", fixture.solicitacaoId().valor()).update();

        // TX-B, composicao identica a ReconciliarEfetivacoes: o Core responde FalhaDefinitiva para
        // ESTE ciclo, mas o terminal PERSISTIDO (EFETIVADA) vence -- nunca o resultado perdedor.
        ResultadoRegistroEfetivacao registro = transacaoPort.executar(() -> {
            assertThat(adapter.claimAindaValido(claim)).isTrue();
            return registrarResultadoEfetivacao.executar(
                    claim.efetivacaoId(), new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_INEXISTENTE),
                    Optional.empty(), AtorSistema.RECONCILIACAO_EFETIVACAO, AGORA);
        });
        assertThat(registro).isInstanceOf(ResultadoRegistroEfetivacao.JaTerminalContraditoria.class);
        transacaoPort.executar(() -> {
            adapter.terminalizar(claim, AGORA);
            return null;
        });

        assertThat(statusSolicitacaoDe(fixture.solicitacaoId())).isEqualTo("EFETIVADA"); // terminal preservado
        assertThat(statusReconciliacaoDe(fixture.efetivacaoId())).isEqualTo("CONCLUIDA");
    }

    // --- Janela esgotada: indeterminacao dentro da mesma TX-B ------------------------------------

    @Test
    void cicloCompleto_janelaEsgotada_indeterminaNaMesmaTxB_eDisparaAlerta() throws SQLException {
        Instant janelaJaExpirada = AGORA.minusSeconds(1);
        Fixture fixture = criarFixtureAguardandoEfetivacao(
                novaContaId(), 500_000, 600_000, AGORA.minusSeconds(10), janelaJaExpirada);

        FakeAlerta alerta = new FakeAlerta();
        ResultadoCicloReconciliacao ciclo = reconciliar(
                new FakeCore(new ResultadoConsultaStatusCore.EmProcessamento()), alerta, fixoEm(AGORA)).executarUmCiclo();

        assertThat(ciclo).isInstanceOf(ResultadoCicloReconciliacao.IndeterminadaAgora.class);
        assertThat(statusSolicitacaoDe(fixture.solicitacaoId())).isEqualTo("EFETIVACAO_INDETERMINADA");
        assertThat(statusReconciliacaoDe(fixture.efetivacaoId())).isEqualTo("PENDENTE"); // continua, backoff-longo
        assertThat(indeterminadaEmDe(fixture.efetivacaoId())).isNotNull();
        assertThat(claimIdDe(fixture.efetivacaoId())).isNull();
        assertThat(alerta.chamadas).isEqualTo(1);
        assertThat(contarHistorico(fixture.solicitacaoId(), "EFETIVACAO_INDETERMINADA_REGISTRADA")).isEqualTo(1L);
    }

    @Test
    void cicloCompleto_conclusaoTardiaEmFalhaAutoritativaSobreIndeterminada_terminalizaReconciliacao() throws SQLException {
        Fixture fixture = criarFixtureComStatus(
                novaContaId(), "EFETIVACAO_INDETERMINADA", AGORA.minusSeconds(10), AGORA.minus(JANELA));
        appJdbcClient.sql("update reconciliacao_efetivacao set indeterminada_em = :em where efetivacao_id = :id")
                .param("em", Timestamp.from(AGORA.minusSeconds(5)))
                .param("id", fixture.efetivacaoId().valor()).update();

        ResultadoCicloReconciliacao ciclo = reconciliar(
                new FakeCore(new ResultadoConsultaStatusCore.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_INEXISTENTE)),
                new FakeAlerta(), fixoEm(AGORA)).executarUmCiclo();

        assertThat(ciclo).isInstanceOf(ResultadoCicloReconciliacao.ConcluidaPorResultadoAutoritativo.class);
        assertThat(statusSolicitacaoDe(fixture.solicitacaoId())).isEqualTo("FALHA_EFETIVACAO");
        assertThat(statusReconciliacaoDe(fixture.efetivacaoId())).isEqualTo("CONCLUIDA");
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures e utilitarios.
    // ---------------------------------------------------------------------------------------

    private record Fixture(SolicitacaoId solicitacaoId, EfetivacaoId efetivacaoId) {
    }

    private static synchronized ContaId novaContaId() {
        contaSequencial++;
        return new ContaId(Long.toString(contaSequencial));
    }

    private Fixture criarFixtureAguardandoEfetivacao(
            ContaId contaId, long limiteEsperado, long limiteSolicitado, Instant proximaConsultaEm, Instant janelaExpiraEm)
            throws SQLException {
        return criarFixture(contaId, "AGUARDANDO_EFETIVACAO", limiteEsperado, limiteSolicitado, proximaConsultaEm, janelaExpiraEm);
    }

    private Fixture criarFixtureComStatus(ContaId contaId, String status, Instant proximaConsultaEm, Instant janelaExpiraEm)
            throws SQLException {
        return criarFixture(contaId, status, 500_000, 600_000, proximaConsultaEm, janelaExpiraEm);
    }

    private Fixture criarFixture(
            ContaId contaId, String status, long limiteEsperado, long limiteSolicitado,
            Instant proximaConsultaEm, Instant janelaExpiraEm) throws SQLException {
        UUID id = UUID.randomUUID();
        UUID efetivacaoId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Timestamp ts = Timestamp.from(AGORA);

        try (Connection con = DriverManager.getConnection(creditoJdbcUrl, APP_USER, APP_PASSWORD)) {
            executar(con, """
                    insert into solicitacao_aumento_limite
                        (id, cliente_id, conta_id, originador_id, origem_solicitacao, canal_manifestacao,
                         status, correlation_id, efetivacao_id, registrada_em, atualizada_em)
                    values (?, '1', ?, 'gerente.teste', 'CLIENTE', 'PRESENCIAL', ?, ?, ?, ?, ?)
                    """, id, contaId.valor(), status, correlationId, efetivacaoId, ts, ts);

            executar(con, """
                    insert into contexto_decisao_credito
                        (solicitacao_id, limite_cheque_especial_vigente, situacao_conta,
                         classificacao_risco_credito_base, limite_solicitado, incremento_solicitado,
                         versao_politica_credito, capturado_em, dados_credito_core_fonte, dados_credito_core_consultado_em)
                    values (?, ?, 'REGULAR', 'BAIXO', ?, ?, 'v1', ?, 'CORE_LEGADO', ?)
                    """, id, limiteEsperado, limiteSolicitado, limiteSolicitado - limiteEsperado, ts, ts);

            executar(con, """
                    insert into decisao_credito
                        (solicitacao_id, resultado, motivo, versao_politica_credito, decidida_em, autor_tipo, autor_id)
                    values (?, 'APROVADA', 'DENTRO_DA_POLITICA_AUTOMATICA', 'v1', ?, 'SISTEMA', 'MOTOR_DECISAO_CREDITO')
                    """, id, ts);

            executar(con, """
                    insert into reconciliacao_efetivacao
                        (efetivacao_id, status_reconciliacao, tentativas, proxima_consulta_em, janela_expira_em, atualizado_em)
                    values (?, 'PENDENTE', 0, ?, ?, ?)
                    """, efetivacaoId, Timestamp.from(proximaConsultaEm), Timestamp.from(janelaExpiraEm), ts);
        }
        return new Fixture(new SolicitacaoId(id), new EfetivacaoId(efetivacaoId));
    }

    private EfetivacaoReconciliacaoReclamada reclamar() {
        ReclamacaoReconciliacao reclamacao = adapter.reclamarProxima(AGORA, LEASE);
        assertThat(reclamacao).isInstanceOf(ReclamacaoReconciliacao.Reclamada.class);
        return ((ReclamacaoReconciliacao.Reclamada) reclamacao).claim();
    }

    private static PoliticaRetryEntrega politicaRetrySemJitter() {
        return new PoliticaRetryEntrega(Duration.ofSeconds(30), Duration.ofMinutes(2), 0.0, new Random());
    }

    private static ClockFixo fixoEm(Instant instante) {
        return new ClockFixo(instante);
    }

    private ReconciliarEfetivacoes reconciliar(ConsultaStatusEfetivacaoCorePort core, AlertaOperacionalPort alerta, Clock clock) {
        return new ReconciliarEfetivacoes(
                adapter, core, registrarResultadoEfetivacao, alerta, transacaoPort, politicaRetrySemJitter(), clock, LEASE, BACKOFF_LONGO);
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
                    barreira.await();
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

    private Integer tentativasDe(EfetivacaoId efetivacaoId) {
        return appJdbcClient.sql("select tentativas from reconciliacao_efetivacao where efetivacao_id = :id")
                .param("id", efetivacaoId.valor()).query(Integer.class).single();
    }

    private String statusReconciliacaoDe(EfetivacaoId efetivacaoId) {
        return appJdbcClient.sql("select status_reconciliacao from reconciliacao_efetivacao where efetivacao_id = :id")
                .param("id", efetivacaoId.valor()).query(String.class).single();
    }

    private UUID claimIdDe(EfetivacaoId efetivacaoId) {
        return appJdbcClient.sql("select claim_id from reconciliacao_efetivacao where efetivacao_id = :id")
                .param("id", efetivacaoId.valor()).query(UUID.class).optional().orElse(null);
    }

    private Instant indeterminadaEmDe(EfetivacaoId efetivacaoId) {
        Timestamp ts = appJdbcClient.sql("select indeterminada_em from reconciliacao_efetivacao where efetivacao_id = :id")
                .param("id", efetivacaoId.valor()).query(Timestamp.class).optional().orElse(null);
        return ts == null ? null : ts.toInstant();
    }

    private String statusSolicitacaoDe(SolicitacaoId id) {
        return appJdbcClient.sql("select status from solicitacao_aumento_limite where id = :id")
                .param("id", id.valor()).query(String.class).single();
    }

    private String protocoloCoreDe(SolicitacaoId id) {
        return appJdbcClient.sql("select protocolo_core from solicitacao_aumento_limite where id = :id")
                .param("id", id.valor()).query(String.class).optional().orElse(null);
    }

    private Long contarHistorico(SolicitacaoId id, String tipoFato) {
        return appJdbcClient.sql("select count(*) from historico_solicitacao where solicitacao_id = :id and tipo_fato = :tipo")
                .param("id", id.valor()).param("tipo", tipoFato).query(Long.class).single();
    }

    /** {@code Clock} de teste que nunca avanca por conta propria -- os dois instantes lidos por um ciclo sao identicos. */
    private static final class ClockFixo extends Clock {
        private final Instant instante;

        ClockFixo(Instant instante) {
            this.instante = instante;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("nao usado neste teste");
        }

        @Override
        public Instant instant() {
            return instante;
        }
    }

    private static final class FakeCore implements ConsultaStatusEfetivacaoCorePort {
        private final ResultadoConsultaStatusCore resposta;

        FakeCore(ResultadoConsultaStatusCore resposta) {
            this.resposta = resposta;
        }

        @Override
        public ResultadoConsultaStatusCore consultarPorProtocolo(ProtocoloCore protocolo) {
            return resposta;
        }

        @Override
        public ResultadoConsultaStatusCore consultarPorEfetivacaoId(EfetivacaoId efetivacaoId) {
            return resposta;
        }
    }

    private static final class FakeAlerta implements AlertaOperacionalPort {
        int chamadas = 0;

        @Override
        public void efetivacaoIndeterminada(EfetivacaoId efetivacaoId, SolicitacaoId solicitacaoId, Instant ocorridoEm) {
            chamadas++;
        }
    }
}
