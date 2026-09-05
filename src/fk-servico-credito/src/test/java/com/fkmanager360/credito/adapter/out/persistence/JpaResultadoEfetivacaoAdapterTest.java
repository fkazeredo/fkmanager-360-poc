package com.fkmanager360.credito.adapter.out.persistence;

import com.fkmanager360.credito.adapter.out.persistence.repository.SolicitacaoAumentoLimiteRepository;
import com.fkmanager360.credito.application.port.out.EntregaEfetivacaoReclamada;
import com.fkmanager360.credito.application.port.out.EntregasEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ReclamacaoEntrega;
import com.fkmanager360.credito.application.port.out.ResultadoConclusaoDefinitiva;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoRecebido;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroEfetivacao;
import com.fkmanager360.credito.application.usecase.RegistrarResultadoEfetivacao;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3 (ADR-0018): a variante de sucesso de {@code RegistrarResultadoEfetivacao} (#0005) e a
 * convergencia entre o callback ({@link RegistrarResultadoEfetivacao#executar}, via
 * {@link JpaResultadoEfetivacaoAdapter}) e o dispatcher
 * ({@link RegistrarResultadoEfetivacao#executarSobClaim}, via {@link JdbcEntregasEfetivacaoAdapter})
 * contra PostgreSQL real via Testcontainers. Mesmo padrao de {@link JdbcEntregasEfetivacaoAdapterTest}:
 * container proprio, papeis {@code credito_migrator}/{@code credito_app} recriados aqui, IP do
 * container na rede bridge (Docker-outside-of-Docker, ver memoria de projeto "maven-via-docker").
 *
 * <p>Fixtures via SQL cru: cada uma insere diretamente uma {@code SolicitacaoAumentoLimite} em
 * {@code AGUARDANDO_EFETIVACAO} com sua intencao ({@code outbox_mensagem}) e entrega
 * ({@code outbox_entrega}) no estado que o dispatcher encontraria apos TX2 de #0003, e/ou ja
 * concluida a um terminal (para os cenarios de conclusao concorrente).
 */
@Testcontainers
class JpaResultadoEfetivacaoAdapterTest {

    private static final String MIGRATOR_USER = "credito_migrator";
    private static final String MIGRATOR_PASSWORD = "migrator-teste-nao-usar-em-producao";
    private static final String APP_USER = "credito_app";
    private static final String APP_PASSWORD = "app-teste-nao-usar-em-producao";

    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final int MAX_TENTATIVAS = 4;
    private static final Instant AGORA = Instant.parse("2026-09-04T10:00:00Z");
    private static final long LIMITE_ESPERADO = 500_000L;
    private static final long LIMITE_SOLICITADO = 600_000L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static String creditoJdbcUrl;
    private static HikariDataSource appDataSource;
    private static JdbcClient appJdbcClient;
    private static AnnotationConfigApplicationContext applicationContext;
    private static ResultadoEfetivacaoPort resultadoEfetivacaoPort;
    private static EntregasEfetivacaoPort entregasEfetivacaoPort;
    private static RegistrarResultadoEfetivacao registrarResultadoEfetivacao;

    private static long contaSequencial = 9_300_000_000L;

    @BeforeAll
    static void provisionarPapeisMigrarESubirAdaptersComCredencialDeApp() throws Exception {
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
        resultadoEfetivacaoPort = applicationContext.getBean(ResultadoEfetivacaoPort.class);
        entregasEfetivacaoPort = applicationContext.getBean(EntregasEfetivacaoPort.class);
        registrarResultadoEfetivacao = applicationContext.getBean(RegistrarResultadoEfetivacao.class);
    }

    @AfterAll
    static void fecharRecursos() {
        applicationContext.close();
        appDataSource.close();
    }

    @AfterEach
    void limparTabelas() {
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
    @ComponentScan(basePackageClasses = JdbcEntregasEfetivacaoAdapter.class)
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
                com.fkmanager360.credito.application.port.out.TransacaoPort transacaoPort) {
            return new RegistrarResultadoEfetivacao(resultadoEfetivacaoPort, entregasEfetivacaoPort, transacaoPort);
        }

        @Bean
        static PersistenceExceptionTranslationPostProcessor persistenceExceptionTranslationPostProcessor() {
            return new PersistenceExceptionTranslationPostProcessor();
        }

        // #0006: CreditoPersistenceOperations (mesmo pacote, entra pelo ComponentScan acima) ganhou
        // dois construtor-params @Value (janela de reconciliacao) -- os dois beans abaixo replicam,
        // neste contexto minimo de Spring Framework puro, o que uma aplicacao Spring Boot de
        // verdade ja da de graca via autoconfiguracao: resolucao de "${...:default}" e conversao
        // String -> java.time.Duration. Nenhuma property e definida de proposito -- os testes
        // exercitam os DEFAULTS de application.yml (PT60S / PT10M).
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
    // Callback direto (executar, sem claim): sucesso, duplicado (AC13), incoerente (AC26).
    // ---------------------------------------------------------------------------------------

    @Test
    void executar_sucessoCoerente_concluiEfetivadaEAprendeProtocolo() throws SQLException {
        Fixture fixture = criarSolicitacaoAguardandoEfetivacao(novaContaId());

        ResultadoRegistroEfetivacao resultado = registrarResultadoEfetivacao.executar(
                fixture.efetivacaoId(), new ResultadoEfetivacaoRecebido.Sucesso(LIMITE_SOLICITADO),
                Optional.of(new ProtocoloCore("PRT-CB-1")), AtorSistema.CORE_LEGADO, AGORA);

        assertThat(resultado).isInstanceOf(ResultadoRegistroEfetivacao.Concluida.class);
        assertThat(statusSolicitacaoDe(fixture.solicitacaoId())).isEqualTo("EFETIVADA");
        assertThat(protocoloCoreDe(fixture.solicitacaoId())).isEqualTo("PRT-CB-1");
        assertThat(contarHistorico(fixture.solicitacaoId(), "INSTRUCAO_ACEITA_PELO_CORE")).isEqualTo(1L);
        assertThat(contarHistorico(fixture.solicitacaoId(), "RESULTADO_EFETIVACAO_REGISTRADO")).isEqualTo(1L);
    }

    @Test
    void executar_callbackDuplicadoIdentico_naoAlteraEstadoNemDuplicaHistorico() throws SQLException {
        Fixture fixture = criarSolicitacaoAguardandoEfetivacao(novaContaId());
        registrarResultadoEfetivacao.executar(
                fixture.efetivacaoId(), new ResultadoEfetivacaoRecebido.Sucesso(LIMITE_SOLICITADO),
                Optional.of(new ProtocoloCore("PRT-CB-1")), AtorSistema.CORE_LEGADO, AGORA);

        ResultadoRegistroEfetivacao segunda = registrarResultadoEfetivacao.executar(
                fixture.efetivacaoId(), new ResultadoEfetivacaoRecebido.Sucesso(LIMITE_SOLICITADO),
                Optional.of(new ProtocoloCore("PRT-CB-1")), AtorSistema.CORE_LEGADO, AGORA.plusSeconds(5));

        assertThat(segunda).isInstanceOf(ResultadoRegistroEfetivacao.JaTerminalIdentica.class);
        assertThat(statusSolicitacaoDe(fixture.solicitacaoId())).isEqualTo("EFETIVADA");
        assertThat(contarHistorico(fixture.solicitacaoId(), "INSTRUCAO_ACEITA_PELO_CORE")).isEqualTo(1L);
        assertThat(contarHistorico(fixture.solicitacaoId(), "RESULTADO_EFETIVACAO_REGISTRADO")).isEqualTo(1L);
    }

    @Test
    void executar_callbackContraditorioSobreTerminal_naoReescreveEstado() throws SQLException {
        Fixture fixture = criarSolicitacaoAguardandoEfetivacao(novaContaId());
        registrarResultadoEfetivacao.executar(
                fixture.efetivacaoId(), new ResultadoEfetivacaoRecebido.Sucesso(LIMITE_SOLICITADO),
                Optional.of(new ProtocoloCore("PRT-CB-1")), AtorSistema.CORE_LEGADO, AGORA);

        ResultadoRegistroEfetivacao contraditorio = registrarResultadoEfetivacao.executar(
                fixture.efetivacaoId(), new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_INEXISTENTE),
                Optional.of(new ProtocoloCore("PRT-CB-1")), AtorSistema.CORE_LEGADO, AGORA.plusSeconds(5));

        assertThat(contraditorio).isInstanceOf(ResultadoRegistroEfetivacao.JaTerminalContraditoria.class);
        assertThat(statusSolicitacaoDe(fixture.solicitacaoId())).isEqualTo("EFETIVADA");
        assertThat(motivoFalhaDe(fixture.solicitacaoId())).isNull();
        assertThat(contarHistorico(fixture.solicitacaoId(), "RESULTADO_EFETIVACAO_REGISTRADO")).isEqualTo(1L);
    }

    @Test
    void executar_sucessoIncoerente_naoTransicionaEPermaneceRecuperavel() throws SQLException {
        Fixture fixture = criarSolicitacaoAguardandoEfetivacao(novaContaId());

        ResultadoRegistroEfetivacao incoerente = registrarResultadoEfetivacao.executar(
                fixture.efetivacaoId(), new ResultadoEfetivacaoRecebido.Sucesso(LIMITE_SOLICITADO + 1),
                Optional.of(new ProtocoloCore("PRT-CB-1")), AtorSistema.CORE_LEGADO, AGORA);

        assertThat(incoerente).isInstanceOf(ResultadoRegistroEfetivacao.SucessoIncoerente.class);
        assertThat(statusSolicitacaoDe(fixture.solicitacaoId())).isEqualTo("AGUARDANDO_EFETIVACAO");
        assertThat(protocoloCoreDe(fixture.solicitacaoId())).isNull();

        // Um resultado autoritativo coerente posterior ainda a conclui.
        ResultadoRegistroEfetivacao coerente = registrarResultadoEfetivacao.executar(
                fixture.efetivacaoId(), new ResultadoEfetivacaoRecebido.Sucesso(LIMITE_SOLICITADO),
                Optional.of(new ProtocoloCore("PRT-CB-1")), AtorSistema.CORE_LEGADO, AGORA.plusSeconds(5));
        assertThat(coerente).isInstanceOf(ResultadoRegistroEfetivacao.Concluida.class);
        assertThat(statusSolicitacaoDe(fixture.solicitacaoId())).isEqualTo("EFETIVADA");
    }

    // ---------------------------------------------------------------------------------------
    // AC14: callback antecipado conclui primeiro; TX-B do dispatcher (registrarAceite) chega
    // depois e converge sem regredir nada.
    // ---------------------------------------------------------------------------------------

    @Test
    void callbackAntecipado_concluiPrimeiro_eTxBDoDispatcherConvergeSemRegredir() throws SQLException {
        Fixture fixture = criarSolicitacaoAguardandoEfetivacaoComEntregaPendente(novaContaId());
        EntregaEfetivacaoReclamada claim = reclamar(fixture);

        // (1)-(5): o callback chega ANTES da TX-B do dispatcher, aprende o protocolo, registra
        // ACEITE: e RESULTADO:, conclui EFETIVADA.
        ResultadoRegistroEfetivacao doCallback = registrarResultadoEfetivacao.executar(
                fixture.efetivacaoId(), new ResultadoEfetivacaoRecebido.Sucesso(LIMITE_SOLICITADO),
                Optional.of(new ProtocoloCore("PRT-ANTECIPADO")), AtorSistema.CORE_LEGADO, AGORA);
        assertThat(doCallback).isInstanceOf(ResultadoRegistroEfetivacao.Concluida.class);

        // (6): a TX-B original do dispatcher chega depois com o aceite -- mesmo protocolo,
        // reconhecido como ja conhecido; NUNCA reescreve status; ainda termina a entrega ACEITA;
        // NAO duplica o fato ACEITE:.
        var resultadoTxB = entregasEfetivacaoPort.registrarAceite(claim, new ProtocoloCore("PRT-ANTECIPADO"), AGORA.plusSeconds(2));

        assertThat(resultadoTxB).isEqualTo(com.fkmanager360.credito.application.port.out.ResultadoRegistroEntrega.APLICADO);
        assertThat(statusSolicitacaoDe(fixture.solicitacaoId())).isEqualTo("EFETIVADA");
        assertThat(protocoloCoreDe(fixture.solicitacaoId())).isEqualTo("PRT-ANTECIPADO");
        assertThat(statusEntregaDe(fixture.messageId())).isEqualTo("ACEITA");
        assertThat(claimIdDe(fixture.messageId())).isNull();
        assertThat(contarHistorico(fixture.solicitacaoId(), "INSTRUCAO_ACEITA_PELO_CORE")).isEqualTo(1L);
        assertThat(contarHistorico(fixture.solicitacaoId(), "RESULTADO_EFETIVACAO_REGISTRADO")).isEqualTo(1L);
    }

    // ---------------------------------------------------------------------------------------
    // Conclusao concorrente (#0005, guardrail normativo do Owner): duas direcoes contra o banco.
    // ---------------------------------------------------------------------------------------

    @Test
    void executarSobClaim_callbackDeSucessoJaConcluiuAntesDoDispatcher_terminalObservadoVenceContraOBanco() throws SQLException {
        Fixture fixture = criarSolicitacaoAguardandoEfetivacaoComEntregaPendente(novaContaId());
        EntregaEfetivacaoReclamada claim = reclamar(fixture);

        registrarResultadoEfetivacao.executar(
                fixture.efetivacaoId(), new ResultadoEfetivacaoRecebido.Sucesso(LIMITE_SOLICITADO),
                Optional.of(new ProtocoloCore("PRT-CB")), AtorSistema.CORE_LEGADO, AGORA);

        ResultadoConclusaoDefinitiva resultado = registrarResultadoEfetivacao.executarSobClaim(
                claim, new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_INEXISTENTE),
                AtorSistema.CORE_LEGADO, AGORA.plusSeconds(1));

        assertThat(resultado).isInstanceOf(ResultadoConclusaoDefinitiva.ConcluidaPorOutroCaminho.class);
        var concluida = (ResultadoConclusaoDefinitiva.ConcluidaPorOutroCaminho) resultado;
        assertThat(concluida.terminalObservado()).isEqualTo(com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite.EFETIVADA);
        assertThat(concluida.contraditoria()).isTrue();

        assertThat(statusSolicitacaoDe(fixture.solicitacaoId())).isEqualTo("EFETIVADA");
        assertThat(motivoFalhaDe(fixture.solicitacaoId())).isNull();
        assertThat(protocoloCoreDe(fixture.solicitacaoId())).isEqualTo("PRT-CB");
        assertThat(statusEntregaDe(fixture.messageId())).isEqualTo("ACEITA");
        assertThat(claimIdDe(fixture.messageId())).isNull();
        assertThat(contarHistorico(fixture.solicitacaoId(), "RESULTADO_EFETIVACAO_REGISTRADO")).isEqualTo(1L);
    }

    @Test
    void executarSobClaim_callbackDeFalhaJaConcluiuAntesDoDispatcher_terminalObservadoVenceContraOBanco() throws SQLException {
        Fixture fixture = criarSolicitacaoAguardandoEfetivacaoComEntregaPendente(novaContaId());
        EntregaEfetivacaoReclamada claim = reclamar(fixture);

        registrarResultadoEfetivacao.executar(
                fixture.efetivacaoId(), new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_BLOQUEADA_NA_EFETIVACAO),
                Optional.empty(), AtorSistema.CORE_LEGADO, AGORA);

        ResultadoConclusaoDefinitiva resultado = registrarResultadoEfetivacao.executarSobClaim(
                claim, new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE),
                AtorSistema.CORE_LEGADO, AGORA.plusSeconds(1));

        assertThat(resultado).isInstanceOf(ResultadoConclusaoDefinitiva.ConcluidaPorOutroCaminho.class);
        var concluida = (ResultadoConclusaoDefinitiva.ConcluidaPorOutroCaminho) resultado;
        assertThat(concluida.terminalObservado()).isEqualTo(com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO);
        assertThat(concluida.contraditoria()).isTrue();

        assertThat(statusSolicitacaoDe(fixture.solicitacaoId())).isEqualTo("FALHA_EFETIVACAO");
        assertThat(motivoFalhaDe(fixture.solicitacaoId())).isEqualTo("CONTA_BLOQUEADA_NA_EFETIVACAO");
        assertThat(statusEntregaDe(fixture.messageId())).isEqualTo("FALHA_DEFINITIVA");
        assertThat(claimIdDe(fixture.messageId())).isNull();
        assertThat(contarHistorico(fixture.solicitacaoId(), "RESULTADO_EFETIVACAO_REGISTRADO")).isEqualTo(1L);
    }

    /**
     * Teste concorrente de verdade: dispatcher segura o lock de {@code outbox_entrega} (fencing,
     * dentro de {@code executarSobClaim}) enquanto o callback concorre pelo lock de
     * {@code solicitacao_aumento_limite} -- as duas threads liberadas simultaneamente por uma
     * barreira. Como {@code executarSobClaim} nunca toma o lock de {@code outbox_entrega} DEPOIS
     * do de {@code solicitacao} (ordem global sempre outbox_entrega -&gt; solicitacao) e o
     * callback nunca toma o de {@code outbox_entrega}, nao ha ciclo possivel -- sem deadlock. O
     * vencedor da corrida pela linha de {@code solicitacao_aumento_limite} e nao-determinado; o
     * teste afirma as invariantes que valem para QUALQUER vencedor: terminal unico e coerente,
     * entrega terminada de acordo com ele, claim liberado, historico sem duplicacao.
     */
    @Test
    void executarSobClaim_concorrenteComCallbackDireto_semDeadlockETerminalPersistidoCoerente() throws Exception {
        Fixture fixture = criarSolicitacaoAguardandoEfetivacaoComEntregaPendente(novaContaId());
        EntregaEfetivacaoReclamada claim = reclamar(fixture);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barreira = new CyclicBarrier(2);

            Future<ResultadoConclusaoDefinitiva> futuroDispatcher = executor.submit(() -> {
                barreira.await(10, TimeUnit.SECONDS);
                return registrarResultadoEfetivacao.executarSobClaim(
                        claim, new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_INEXISTENTE),
                        AtorSistema.CORE_LEGADO, AGORA);
            });
            Future<ResultadoRegistroEfetivacao> futuroCallback = executor.submit(() -> {
                barreira.await(10, TimeUnit.SECONDS);
                return registrarResultadoEfetivacao.executar(
                        fixture.efetivacaoId(), new ResultadoEfetivacaoRecebido.Sucesso(LIMITE_SOLICITADO),
                        Optional.of(new ProtocoloCore("PRT-CONCORRENTE")), AtorSistema.CORE_LEGADO, AGORA);
            });

            ResultadoConclusaoDefinitiva resultadoDispatcher = futuroDispatcher.get(15, TimeUnit.SECONDS);
            ResultadoRegistroEfetivacao resultadoCallback = futuroCallback.get(15, TimeUnit.SECONDS);

            String statusFinal = statusSolicitacaoDe(fixture.solicitacaoId());
            assertThat(statusFinal).isIn("FALHA_EFETIVACAO", "EFETIVADA");

            if ("EFETIVADA".equals(statusFinal)) {
                // Callback venceu a corrida pela linha da solicitacao: aplicou normalmente; o
                // dispatcher, chegando depois, encontrou o terminal ja persistido.
                assertThat(resultadoCallback).isInstanceOf(ResultadoRegistroEfetivacao.Concluida.class);
                assertThat(resultadoDispatcher).isInstanceOf(ResultadoConclusaoDefinitiva.ConcluidaPorOutroCaminho.class);
                assertThat(((ResultadoConclusaoDefinitiva.ConcluidaPorOutroCaminho) resultadoDispatcher).terminalObservado())
                        .isEqualTo(com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite.EFETIVADA);
                assertThat(protocoloCoreDe(fixture.solicitacaoId())).isEqualTo("PRT-CONCORRENTE");
                assertThat(statusEntregaDe(fixture.messageId())).isEqualTo("ACEITA");
                assertThat(contarHistorico(fixture.solicitacaoId(), "INSTRUCAO_ACEITA_PELO_CORE")).isEqualTo(1L);
            } else {
                // Dispatcher venceu: aplicou normalmente; o callback, chegando depois, encontrou
                // FALHA_EFETIVACAO ja persistida e nao sobrescreveu com o Sucesso que trazia.
                assertThat(resultadoDispatcher).isInstanceOf(ResultadoConclusaoDefinitiva.Aplicado.class);
                assertThat(resultadoCallback).isInstanceOf(ResultadoRegistroEfetivacao.JaTerminalContraditoria.class);
                assertThat(motivoFalhaDe(fixture.solicitacaoId())).isEqualTo("CONTA_INEXISTENTE");
                assertThat(protocoloCoreDe(fixture.solicitacaoId())).isNull();
                assertThat(statusEntregaDe(fixture.messageId())).isEqualTo("FALHA_DEFINITIVA");
                assertThat(contarHistorico(fixture.solicitacaoId(), "INSTRUCAO_ACEITA_PELO_CORE")).isZero();
            }

            assertThat(claimIdDe(fixture.messageId())).isNull();
            assertThat(contarHistorico(fixture.solicitacaoId(), "RESULTADO_EFETIVACAO_REGISTRADO")).isEqualTo(1L);
        } finally {
            executor.shutdown();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures e utilitarios.
    // ---------------------------------------------------------------------------------------

    private record Fixture(SolicitacaoId solicitacaoId, UUID messageId, EfetivacaoId efetivacaoId) {
    }

    private static synchronized ContaId novaContaId() {
        contaSequencial++;
        return new ContaId(Long.toString(contaSequencial));
    }

    /** Solicitacao AGUARDANDO_EFETIVACAO sem outbox_entrega -- para os testes do callback puro (executar). */
    private Fixture criarSolicitacaoAguardandoEfetivacao(ContaId contaId) throws SQLException {
        return inserirFixture(contaId, false);
    }

    /** Solicitacao AGUARDANDO_EFETIVACAO com outbox_entrega PENDENTE -- para os testes de convergencia com o dispatcher. */
    private Fixture criarSolicitacaoAguardandoEfetivacaoComEntregaPendente(ContaId contaId) throws SQLException {
        return inserirFixture(contaId, true);
    }

    private Fixture inserirFixture(ContaId contaId, boolean comEntregaPendente) throws SQLException {
        UUID id = UUID.randomUUID();
        UUID efetivacaoId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Timestamp ts = Timestamp.from(AGORA);

        try (Connection con = DriverManager.getConnection(creditoJdbcUrl, APP_USER, APP_PASSWORD)) {
            executar(con, """
                    insert into solicitacao_aumento_limite
                        (id, cliente_id, conta_id, originador_id, origem_solicitacao, canal_manifestacao,
                         status, correlation_id, efetivacao_id, registrada_em, atualizada_em)
                    values (?, '1', ?, 'gerente.teste', 'CLIENTE', 'PRESENCIAL', 'AGUARDANDO_EFETIVACAO', ?, ?, ?, ?)
                    """, id, contaId.valor(), correlationId, efetivacaoId, ts, ts);

            executar(con, """
                    insert into contexto_decisao_credito
                        (solicitacao_id, limite_cheque_especial_vigente, situacao_conta,
                         classificacao_risco_credito_base, limite_solicitado, incremento_solicitado,
                         versao_politica_credito, capturado_em, dados_credito_core_fonte, dados_credito_core_consultado_em)
                    values (?, ?, 'REGULAR', 'BAIXO', ?, ?, 'v1', ?, 'CORE_LEGADO', ?)
                    """, id, LIMITE_ESPERADO, LIMITE_SOLICITADO, LIMITE_SOLICITADO - LIMITE_ESPERADO, ts, ts);

            executar(con, """
                    insert into decisao_credito
                        (solicitacao_id, resultado, motivo, versao_politica_credito, decidida_em, autor_tipo, autor_id)
                    values (?, 'APROVADA', 'DENTRO_DA_POLITICA_AUTOMATICA', 'v1', ?, 'SISTEMA', 'MOTOR_DECISAO_CREDITO')
                    """, id, ts);

            executar(con, """
                    insert into outbox_mensagem
                        (message_id, tipo, destino, solicitacao_id, efetivacao_id, conta_id,
                         limite_cheque_especial_vigente_esperado, limite_solicitado, correlation_id, criado_em)
                    values (?, 'EfetivarLimite', 'CORE_LEGADO', ?, ?, ?, ?, ?, ?, ?)
                    """, messageId, id, efetivacaoId, contaId.valor(), LIMITE_ESPERADO, LIMITE_SOLICITADO, correlationId, ts);

            if (comEntregaPendente) {
                executar(con, """
                        insert into outbox_entrega (message_id, status_entrega, tentativas, proxima_tentativa_em, atualizado_em)
                        values (?, 'PENDENTE', 0, ?, ?)
                        """, messageId, ts, ts);
            }
        }
        return new Fixture(new SolicitacaoId(id), messageId, new EfetivacaoId(efetivacaoId));
    }

    private EntregaEfetivacaoReclamada reclamar(Fixture fixture) {
        ReclamacaoEntrega reclamacao = entregasEfetivacaoPort.reclamarProxima(AGORA, MAX_TENTATIVAS, LEASE);
        assertThat(reclamacao).isInstanceOf(ReclamacaoEntrega.Reclamada.class);
        EntregaEfetivacaoReclamada claim = ((ReclamacaoEntrega.Reclamada) reclamacao).entrega();
        assertThat(claim.intencao().messageId()).isEqualTo(fixture.messageId());
        return claim;
    }

    private String statusSolicitacaoDe(SolicitacaoId id) {
        return appJdbcClient.sql("select status from solicitacao_aumento_limite where id = :id")
                .param("id", id.valor()).query(String.class).single();
    }

    private String protocoloCoreDe(SolicitacaoId id) {
        return appJdbcClient.sql("select protocolo_core from solicitacao_aumento_limite where id = :id")
                .param("id", id.valor()).query(String.class).optional().orElse(null);
    }

    private String motivoFalhaDe(SolicitacaoId id) {
        return appJdbcClient.sql("select motivo_falha_efetivacao from solicitacao_aumento_limite where id = :id")
                .param("id", id.valor()).query(String.class).optional().orElse(null);
    }

    private String statusEntregaDe(UUID messageId) {
        return appJdbcClient.sql("select status_entrega from outbox_entrega where message_id = :messageId")
                .param("messageId", messageId).query(String.class).single();
    }

    private UUID claimIdDe(UUID messageId) {
        return appJdbcClient.sql("select claim_id from outbox_entrega where message_id = :messageId")
                .param("messageId", messageId).query(UUID.class).optional().orElse(null);
    }

    private Long contarHistorico(SolicitacaoId id, String tipoFato) {
        return appJdbcClient.sql("select count(*) from historico_solicitacao where solicitacao_id = :id and tipo_fato = :tipo")
                .param("id", id.valor()).param("tipo", tipoFato).query(Long.class).single();
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
