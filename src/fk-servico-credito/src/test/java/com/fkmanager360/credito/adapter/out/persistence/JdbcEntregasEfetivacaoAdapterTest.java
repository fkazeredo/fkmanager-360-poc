package com.fkmanager360.credito.adapter.out.persistence;

import com.fkmanager360.credito.application.port.out.EntregaEfetivacaoReclamada;
import com.fkmanager360.credito.application.port.out.EntregasEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ReclamacaoEntrega;
import com.fkmanager360.credito.application.port.out.ResultadoConclusaoDefinitiva;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoRecebido;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroEntrega;
import com.fkmanager360.credito.application.port.out.TransacaoPort;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3 (ADR-0018): claim/fencing/lifecycle de {@code outbox_entrega} contra PostgreSQL real via
 * Testcontainers, aplicando literalmente {@code infra/postgres-init/02-credito.sh} e as migrations
 * reais (V1 + V2). H2 nao substitui.
 *
 * <p>Mesmo padrao de {@link JpaSolicitacoesAumentoLimiteAdapterTest}: container proprio, papeis
 * {@code credito_migrator}/{@code credito_app} recriados aqui, {@link #adapter} obtido pelo TIPO
 * DA PORT (nao pelo tipo concreto -- {@code @Repository} ativa proxy AOP de traducao de excecao) a
 * partir de um contexto Spring minimo montado sobre a credencial de app.
 *
 * <p><b>Fixtures via SQL cru, nao via TX1/TX2.</b> Este teste prova o DISPATCHER, nao a submissao
 * -- ja provada exaustivamente em {@link JpaSolicitacoesAumentoLimiteAdapterTest}. Cada fixture
 * insere diretamente uma {@code SolicitacaoAumentoLimite} em {@code AGUARDANDO_EFETIVACAO} com sua
 * intencao ({@code outbox_mensagem}) e entrega ({@code outbox_entrega}) ja no estado que o
 * dispatcher encontraria apos TX2 de #0003.
 */
@Testcontainers
class JdbcEntregasEfetivacaoAdapterTest {

    private static final String MIGRATOR_USER = "credito_migrator";
    private static final String MIGRATOR_PASSWORD = "migrator-teste-nao-usar-em-producao";
    private static final String APP_USER = "credito_app";
    private static final String APP_PASSWORD = "app-teste-nao-usar-em-producao";

    private static final int MAX_TENTATIVAS = 4;
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Instant AGORA = Instant.parse("2026-09-03T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static String creditoJdbcUrl;
    private static HikariDataSource appDataSource;
    private static JdbcClient appJdbcClient;
    private static AnnotationConfigApplicationContext applicationContext;
    private static EntregasEfetivacaoPort adapter;
    private static RegistrarResultadoEfetivacao registrarResultadoEfetivacao;

    private static long contaSequencial = 9_200_000_000L;

    @BeforeAll
    static void provisionarPapeisMigrarESubirAdapterComCredencialDeApp() throws Exception {
        // O test runner deste modulo tambem roda dentro de um container (Docker-outside-of-Docker,
        // socket montado -- ver memoria de projeto "maven-via-docker"). Neste ambiente, o mapeamento
        // de porta publicada do Postgres (host:portaMapeada, via gateway da bridge) mostrou-se
        // consistentemente inalcancavel a partir de outro container, mesmo com o container do
        // Postgres saudavel e a porta corretamente publicada (verificado empiricamente) -- o
        // proprio IP do container na rede bridge, porta 5432 direta, e sempre alcancavel. Usar o IP
        // do container evita depender do proxy de porta publicada do Docker Desktop.
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
        adapter = applicationContext.getBean(EntregasEfetivacaoPort.class);
        registrarResultadoEfetivacao = applicationContext.getBean(RegistrarResultadoEfetivacao.class);
    }

    @AfterAll
    static void fecharRecursos() {
        applicationContext.close();
        appDataSource.close();
    }

    /**
     * Isolamento entre testes: {@code reclamarProxima} enxerga TODA linha PENDENTE elegivel na
     * tabela, entao um teste que afirma "nenhuma pendente" ou "exatamente estas duas" precisa de
     * uma tabela vazia no inicio, independente da ordem (nao especificada) em que o JUnit executa
     * os metodos desta classe.
     */
    @AfterEach
    void limparTabelas() {
        appJdbcClient.sql("delete from outbox_entrega").update();
        appJdbcClient.sql("delete from historico_solicitacao").update();
        appJdbcClient.sql("delete from outbox_mensagem").update();
        appJdbcClient.sql("delete from decisao_credito").update();
        appJdbcClient.sql("delete from contexto_decisao_credito").update();
        appJdbcClient.sql("delete from solicitacao_aumento_limite").update();
    }

    /**
     * O container Postgres reinicia uma vez internamente (initdb -&gt; shutdown -&gt; start real);
     * a primeira tentativa de conexao pode chegar exatamente na janela do restart e ser recusada
     * mesmo depois do container ser reportado como "started". Retry curto, sem sleep longo.
     */
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

    /**
     * IP do container na rede {@code bridge} -- alcancavel a partir de outro container mesmo
     * quando o mapeamento de porta publicada (host:portaMapeada) nao esta, neste ambiente de
     * execucao (ver comentario em {@link #provisionarPapeisMigrarESubirAdapterComCredencialDeApp}).
     */
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

        // RegistrarResultadoEfetivacao (application.usecase) fica fora do ComponentScan acima
        // (que so cobre adapter.out.persistence, de proposito -- o caso de uso e Java puro, sem
        // anotacao Spring alguma). As tres portas que ele compoe (JpaResultadoEfetivacaoAdapter,
        // JdbcEntregasEfetivacaoAdapter e TransacaoAdapter) entram pelo ComponentScan normalmente.
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
    }

    // ---------------------------------------------------------------------------------------
    // reclamarProxima: claim unitario, fencing token, historico na 1a tentativa.
    // ---------------------------------------------------------------------------------------

    @Test
    void reclamarProxima_semEntregaPendente_devolveNenhumaPendente() {
        ReclamacaoEntrega reclamacao = adapter.reclamarProxima(AGORA, MAX_TENTATIVAS, LEASE);

        assertThat(reclamacao).isInstanceOf(ReclamacaoEntrega.NenhumaPendente.class);
    }

    @Test
    void reclamarProxima_entregaPendente_geraClaimEIncrementaTentativas_eEscreveHistoricoNaPrimeiraTentativa() throws SQLException {
        Fixture fixture = criarSolicitacaoAguardandoEfetivacaoComEntregaPendente(novaContaId(), 500_000, 600_000);

        ReclamacaoEntrega reclamacao = adapter.reclamarProxima(AGORA, MAX_TENTATIVAS, LEASE);

        assertThat(reclamacao).isInstanceOf(ReclamacaoEntrega.Reclamada.class);
        EntregaEfetivacaoReclamada claim = ((ReclamacaoEntrega.Reclamada) reclamacao).entrega();
        assertThat(claim.tentativaAtual()).isEqualTo(1);
        assertThat(claim.intencao().efetivacaoId()).isEqualTo(fixture.intencaoEfetivacaoId());
        assertThat(claim.intencao().messageId()).isEqualTo(fixture.messageId());
        assertThat(claim.solicitacaoId()).isEqualTo(fixture.solicitacaoId());

        assertThat(tentativasDe(fixture.messageId())).isEqualTo(1);
        assertThat(claimIdDe(fixture.messageId())).isEqualTo(claim.claimId());
        assertThat(contarHistorico(fixture.solicitacaoId(), "EFETIVACAO_SOLICITADA")).isEqualTo(1L);
    }

    @Test
    void reclamarProxima_duasChamadasSeguidas_naoReclamaAMesmaEntregaDuasVezes() throws SQLException {
        Fixture fixture = criarSolicitacaoAguardandoEfetivacaoComEntregaPendente(novaContaId(), 500_000, 600_000);

        adapter.reclamarProxima(AGORA, MAX_TENTATIVAS, LEASE);
        // Lease ainda valido: a segunda chamada, no mesmo instante, nao encontra nada elegivel.
        ReclamacaoEntrega segunda = adapter.reclamarProxima(AGORA, MAX_TENTATIVAS, LEASE);

        assertThat(segunda).isInstanceOf(ReclamacaoEntrega.NenhumaPendente.class);
        assertThat(tentativasDe(fixture.messageId())).isEqualTo(1);
    }

    @Test
    void reclamarProxima_duasEntregasPendentesEDuasThreadsConcorrentes_reclamaConjuntosDisjuntos() throws Exception {
        Fixture f1 = criarSolicitacaoAguardandoEfetivacaoComEntregaPendente(novaContaId(), 500_000, 600_000);
        Fixture f2 = criarSolicitacaoAguardandoEfetivacaoComEntregaPendente(novaContaId(), 500_000, 600_000);

        var resultados = executarConcorrentemente(
                () -> adapter.reclamarProxima(AGORA, MAX_TENTATIVAS, LEASE),
                () -> adapter.reclamarProxima(AGORA, MAX_TENTATIVAS, LEASE));

        var messageIdsReclamados = resultados.stream()
                .filter(r -> r instanceof ReclamacaoEntrega.Reclamada)
                .map(r -> ((ReclamacaoEntrega.Reclamada) r).entrega().intencao().messageId())
                .toList();

        assertThat(messageIdsReclamados).hasSize(2).containsExactlyInAnyOrder(f1.messageId(), f2.messageId());
    }

    // ---------------------------------------------------------------------------------------
    // Desfechos normais (aceite / transitorio / indeterminado / definitivo).
    // ---------------------------------------------------------------------------------------

    @Test
    void registrarAceite_persisteProtocoloEFechaEntregaComoAceita() throws SQLException {
        Fixture fixture = criarSolicitacaoAguardandoEfetivacaoComEntregaPendente(novaContaId(), 500_000, 600_000);
        EntregaEfetivacaoReclamada claim = reclamar(fixture);

        ResultadoRegistroEntrega resultado = adapter.registrarAceite(claim, new ProtocoloCore("PRT-1"), AGORA);

        assertThat(resultado).isEqualTo(ResultadoRegistroEntrega.APLICADO);
        assertThat(statusEntregaDe(fixture.messageId())).isEqualTo("ACEITA");
        assertThat(claimIdDe(fixture.messageId())).isNull();
        assertThat(protocoloCoreDe(fixture.solicitacaoId())).isEqualTo("PRT-1");
        assertThat(statusSolicitacaoDe(fixture.solicitacaoId())).isEqualTo("AGUARDANDO_EFETIVACAO");
        assertThat(contarHistorico(fixture.solicitacaoId(), "INSTRUCAO_ACEITA_PELO_CORE")).isEqualTo(1L);
    }

    @Test
    void registrarAceite_protocoloDivergenteParaOMesmoEfetivacaoId_naoSobrescreveOExistente() throws SQLException {
        Fixture fixture = criarSolicitacaoAguardandoEfetivacaoComEntregaPendente(novaContaId(), 500_000, 600_000);
        EntregaEfetivacaoReclamada claim1 = reclamar(fixture);
        adapter.registrarAceite(claim1, new ProtocoloCore("PRT-ORIGINAL"), AGORA);

        // Forca uma segunda reclamacao artificialmente (simula reprocessamento indevido) so para
        // exercitar o branch de divergencia -- em operacao normal a entrega ja fechou ACEITA e
        // nunca seria reclamada de novo.
        reabrirComoPendente(fixture.messageId());
        EntregaEfetivacaoReclamada claim2 = reclamar(fixture);

        ResultadoRegistroEntrega resultado = adapter.registrarAceite(claim2, new ProtocoloCore("PRT-DIVERGENTE"), AGORA);

        assertThat(resultado).isEqualTo(ResultadoRegistroEntrega.APLICADO_COM_ANOMALIA_PROTOCOLO_DIVERGENTE);
        assertThat(protocoloCoreDe(fixture.solicitacaoId())).isEqualTo("PRT-ORIGINAL");
    }

    @Test
    void reagendar_liberaClaimEVoltaAPendenteComNovoPrazo_semTocarSolicitacao() throws SQLException {
        Fixture fixture = criarSolicitacaoAguardandoEfetivacaoComEntregaPendente(novaContaId(), 500_000, 600_000);
        EntregaEfetivacaoReclamada claim = reclamar(fixture);
        Instant proximaTentativa = AGORA.plusSeconds(2);

        ResultadoRegistroEntrega resultado = adapter.reagendar(claim, proximaTentativa, "timeout", AGORA);

        assertThat(resultado).isEqualTo(ResultadoRegistroEntrega.APLICADO);
        assertThat(statusEntregaDe(fixture.messageId())).isEqualTo("PENDENTE");
        assertThat(claimIdDe(fixture.messageId())).isNull();
        assertThat(proximaTentativaDe(fixture.messageId())).isEqualTo(proximaTentativa);
        assertThat(statusSolicitacaoDe(fixture.solicitacaoId())).isEqualTo("AGUARDANDO_EFETIVACAO");
    }

    @Test
    void marcarIndeterminada_fechaEntregaSemConcluirNadaNaSolicitacao() throws SQLException {
        Fixture fixture = criarSolicitacaoAguardandoEfetivacaoComEntregaPendente(novaContaId(), 500_000, 600_000);
        EntregaEfetivacaoReclamada claim = reclamar(fixture);

        ResultadoRegistroEntrega resultado = adapter.marcarIndeterminada(claim, "COD-RET desconhecido", AGORA);

        assertThat(resultado).isEqualTo(ResultadoRegistroEntrega.APLICADO);
        assertThat(statusEntregaDe(fixture.messageId())).isEqualTo("INDETERMINADA");
        assertThat(statusSolicitacaoDe(fixture.solicitacaoId())).isEqualTo("AGUARDANDO_EFETIVACAO");
        assertThat(motivoFalhaDe(fixture.solicitacaoId())).isNull();
    }

    @Test
    void conclusaoDefinitivaSobClaim_transicionaSolicitacaoParaFalhaEfetivacao_eFechaEntrega() throws SQLException {
        Fixture fixture = criarSolicitacaoAguardandoEfetivacaoComEntregaPendente(novaContaId(), 500_000, 600_000);
        EntregaEfetivacaoReclamada claim = reclamar(fixture);

        ResultadoConclusaoDefinitiva resultado = registrarResultadoEfetivacao.executarSobClaim(
                claim, new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE),
                AtorSistema.CORE_LEGADO, AGORA);

        assertThat(resultado).isInstanceOf(ResultadoConclusaoDefinitiva.Aplicado.class);
        assertThat(((ResultadoConclusaoDefinitiva.Aplicado) resultado).permanenciaEmAguardandoEfetivacao()).isNotNull();
        assertThat(statusEntregaDe(fixture.messageId())).isEqualTo("FALHA_DEFINITIVA");
        assertThat(statusSolicitacaoDe(fixture.solicitacaoId())).isEqualTo("FALHA_EFETIVACAO");
        assertThat(motivoFalhaDe(fixture.solicitacaoId())).isEqualTo("LIMITE_VIGENTE_DIVERGENTE");
        assertThat(contarHistorico(fixture.solicitacaoId(), "RESULTADO_EFETIVACAO_REGISTRADO")).isEqualTo(1L);
    }

    // ---------------------------------------------------------------------------------------
    // Fencing adversarial (correcao do Owner sobre OD-1): claim obsoleto nunca aplica efeito.
    // ---------------------------------------------------------------------------------------

    /**
     * (1) A obtem claim A; (2) lease A expira; (3) B obtem claim B (nova reclamacao apos o
     * lease expirar); (4) B persiste ACEITE; (5) A tenta persistir cada um dos outros tres
     * desfechos; (6) o estado permanece exatamente o que B persistiu -- nada de A e aplicado, nem
     * mesmo metrica de resultado (o retorno DESCARTADO_CLAIM_OBSOLETO e o unico efeito observavel).
     */
    @Test
    void fencing_claimObsoleto_apresentadoAposBAceitar_naoAlteraNadaEmTransitorioDefinitivoOuIndeterminado() throws SQLException {
        Fixture fixture = criarSolicitacaoAguardandoEfetivacaoComEntregaPendente(novaContaId(), 500_000, 600_000);
        EntregaEfetivacaoReclamada claimA = reclamar(fixture);
        Instant depoisDoLeaseExpirar = AGORA.plus(LEASE).plusSeconds(1);

        ReclamacaoEntrega reclamacaoB = adapter.reclamarProxima(depoisDoLeaseExpirar, MAX_TENTATIVAS, LEASE);
        EntregaEfetivacaoReclamada claimB = ((ReclamacaoEntrega.Reclamada) reclamacaoB).entrega();
        assertThat(claimB.claimId()).isNotEqualTo(claimA.claimId());

        adapter.registrarAceite(claimB, new ProtocoloCore("PRT-B"), depoisDoLeaseExpirar);

        assertThat(adapter.reagendar(claimA, depoisDoLeaseExpirar.plusSeconds(1), "obsoleto", depoisDoLeaseExpirar))
                .isEqualTo(ResultadoRegistroEntrega.DESCARTADO_CLAIM_OBSOLETO);
        assertThat(adapter.marcarIndeterminada(claimA, "obsoleto", depoisDoLeaseExpirar))
                .isEqualTo(ResultadoRegistroEntrega.DESCARTADO_CLAIM_OBSOLETO);
        assertThat(registrarResultadoEfetivacao.executarSobClaim(
                claimA, new ResultadoEfetivacaoRecebido.FalhaDefinitiva(MotivoFalhaEfetivacao.CONTA_INEXISTENTE),
                AtorSistema.CORE_LEGADO, depoisDoLeaseExpirar))
                .isInstanceOf(ResultadoConclusaoDefinitiva.DescartadoClaimObsoleto.class);

        assertThat(statusEntregaDe(fixture.messageId())).isEqualTo("ACEITA");
        assertThat(protocoloCoreDe(fixture.solicitacaoId())).isEqualTo("PRT-B");
        assertThat(statusSolicitacaoDe(fixture.solicitacaoId())).isEqualTo("AGUARDANDO_EFETIVACAO");
        assertThat(motivoFalhaDe(fixture.solicitacaoId())).isNull();
    }

    /** Ordem inversa: B reagenda (TRANSITORIO) primeiro; o ACEITE tardio de A e descartado depois. */
    @Test
    void fencing_claimObsoleto_apresentadoAposBReagendar_aceiteTardioDeANaoSobrescreve() throws SQLException {
        Fixture fixture = criarSolicitacaoAguardandoEfetivacaoComEntregaPendente(novaContaId(), 500_000, 600_000);
        EntregaEfetivacaoReclamada claimA = reclamar(fixture);
        Instant depoisDoLeaseExpirar = AGORA.plus(LEASE).plusSeconds(1);

        ReclamacaoEntrega reclamacaoB = adapter.reclamarProxima(depoisDoLeaseExpirar, MAX_TENTATIVAS, LEASE);
        EntregaEfetivacaoReclamada claimB = ((ReclamacaoEntrega.Reclamada) reclamacaoB).entrega();

        Instant proximaTentativa = depoisDoLeaseExpirar.plusSeconds(2);
        adapter.reagendar(claimB, proximaTentativa, "timeout", depoisDoLeaseExpirar);

        ResultadoRegistroEntrega resultadoDeA =
                adapter.registrarAceite(claimA, new ProtocoloCore("PRT-TARDIO-DE-A"), depoisDoLeaseExpirar);

        assertThat(resultadoDeA).isEqualTo(ResultadoRegistroEntrega.DESCARTADO_CLAIM_OBSOLETO);
        assertThat(statusEntregaDe(fixture.messageId())).isEqualTo("PENDENTE");
        assertThat(proximaTentativaDe(fixture.messageId())).isEqualTo(proximaTentativa);
        assertThat(protocoloCoreDe(fixture.solicitacaoId())).isNull();
    }

    // ---------------------------------------------------------------------------------------
    // Esgotamento e crash entre claim e HTTP (secao 1 do plano, regra normativa do Owner).
    // ---------------------------------------------------------------------------------------

    @Test
    void esgotamento_transitoriosConsomemTodasAsTentativas_dispatcherParaSemFalhaEfetivacao() throws SQLException {
        Fixture fixture = criarSolicitacaoAguardandoEfetivacaoComEntregaPendente(novaContaId(), 500_000, 600_000);
        Instant agora = AGORA;

        for (int i = 0; i < MAX_TENTATIVAS; i++) {
            EntregaEfetivacaoReclamada claim = reclamarEm(agora);
            adapter.reagendar(claim, agora.plusSeconds(1), "timeout", agora);
            agora = agora.plusSeconds(1);
        }

        ReclamacaoEntrega ultimaChamada = adapter.reclamarProxima(agora, MAX_TENTATIVAS, LEASE);

        assertThat(ultimaChamada).isInstanceOf(ReclamacaoEntrega.EsgotadaAgora.class);
        assertThat(statusEntregaDe(fixture.messageId())).isEqualTo("ESGOTADA");
        assertThat(tentativasDe(fixture.messageId())).isEqualTo(MAX_TENTATIVAS);
        assertThat(statusSolicitacaoDe(fixture.solicitacaoId())).isEqualTo("AGUARDANDO_EFETIVACAO");
        assertThat(motivoFalhaDe(fixture.solicitacaoId())).isNull();
    }

    /**
     * Regra normativa do Owner: worker reclama a ULTIMA tentativa permitida e o processo morre
     * antes do HTTP/TX-B -- nenhum episodio HTTP e reservado indevidamente, e a proxima
     * reclamacao (apos o lease expirar) termina ESGOTADA diretamente, sob o mesmo lock que a
     * reclamaria, sem incrementar tentativas de novo.
     */
    @Test
    void crashAposReservarAUltimaTentativa_terminalizaEsgotadaSemNovoEpisodio() throws SQLException {
        Fixture fixture = criarSolicitacaoAguardandoEfetivacaoComEntregaPendente(novaContaId(), 500_000, 600_000);
        forcarTentativas(fixture.messageId(), MAX_TENTATIVAS - 1);

        // Worker reclama a ultima tentativa permitida (tentativas: 3 -> 4) e "morre" -- nunca
        // chama reagendar/registrarAceite/etc.
        EntregaEfetivacaoReclamada claim = reclamar(fixture);
        assertThat(claim.tentativaAtual()).isEqualTo(MAX_TENTATIVAS);

        Instant depoisDoLeaseExpirar = AGORA.plus(LEASE).plusSeconds(1);
        ReclamacaoEntrega proximoTick = adapter.reclamarProxima(depoisDoLeaseExpirar, MAX_TENTATIVAS, LEASE);

        assertThat(proximoTick).isInstanceOf(ReclamacaoEntrega.EsgotadaAgora.class);
        assertThat(tentativasDe(fixture.messageId())).isEqualTo(MAX_TENTATIVAS);
        assertThat(statusEntregaDe(fixture.messageId())).isEqualTo("ESGOTADA");
        assertThat(statusSolicitacaoDe(fixture.solicitacaoId())).isEqualTo("AGUARDANDO_EFETIVACAO");
    }

    /** Duas instancias concorrentes nao terminalizam a mesma entrega esgotada duas vezes. */
    @Test
    void crashAposReservarAUltimaTentativa_duasInstanciasConcorrentes_naoDuplicaATerminalizacao() throws Exception {
        Fixture fixture = criarSolicitacaoAguardandoEfetivacaoComEntregaPendente(novaContaId(), 500_000, 600_000);
        forcarTentativas(fixture.messageId(), MAX_TENTATIVAS);
        Instant depoisDoLeaseExpirar = AGORA.plus(LEASE).plusSeconds(1);

        var resultados = executarConcorrentemente(
                () -> adapter.reclamarProxima(depoisDoLeaseExpirar, MAX_TENTATIVAS, LEASE),
                () -> adapter.reclamarProxima(depoisDoLeaseExpirar, MAX_TENTATIVAS, LEASE));

        long esgotamentosNestaChamada = resultados.stream().filter(r -> r instanceof ReclamacaoEntrega.EsgotadaAgora).count();
        long semNadaParaFazer = resultados.stream().filter(r -> r instanceof ReclamacaoEntrega.NenhumaPendente).count();

        assertThat(esgotamentosNestaChamada).isEqualTo(1L);
        assertThat(semNadaParaFazer).isEqualTo(1L);
        assertThat(statusEntregaDe(fixture.messageId())).isEqualTo("ESGOTADA");
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures e utilitarios.
    // ---------------------------------------------------------------------------------------

    private record Fixture(SolicitacaoId solicitacaoId, UUID messageId, EfetivacaoId intencaoEfetivacaoId) {
    }

    private static synchronized ContaId novaContaId() {
        contaSequencial++;
        return new ContaId(Long.toString(contaSequencial));
    }

    private Fixture criarSolicitacaoAguardandoEfetivacaoComEntregaPendente(
            ContaId contaId, long limiteEsperado, long limiteSolicitado) throws SQLException {
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
                    """, id, limiteEsperado, limiteSolicitado, limiteSolicitado - limiteEsperado, ts, ts);

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
                    """, messageId, id, efetivacaoId, contaId.valor(), limiteEsperado, limiteSolicitado, correlationId, ts);

            executar(con, """
                    insert into outbox_entrega (message_id, status_entrega, tentativas, proxima_tentativa_em, atualizado_em)
                    values (?, 'PENDENTE', 0, ?, ?)
                    """, messageId, ts, ts);
        }
        return new Fixture(new SolicitacaoId(id), messageId, new EfetivacaoId(efetivacaoId));
    }

    private EntregaEfetivacaoReclamada reclamar(Fixture fixture) {
        EntregaEfetivacaoReclamada claim = reclamarEm(AGORA);
        assertThat(claim.intencao().messageId()).isEqualTo(fixture.messageId());
        return claim;
    }

    private EntregaEfetivacaoReclamada reclamarEm(Instant agora) {
        ReclamacaoEntrega reclamacao = adapter.reclamarProxima(agora, MAX_TENTATIVAS, LEASE);
        assertThat(reclamacao).isInstanceOf(ReclamacaoEntrega.Reclamada.class);
        return ((ReclamacaoEntrega.Reclamada) reclamacao).entrega();
    }

    private void reabrirComoPendente(UUID messageId) {
        appJdbcClient.sql("""
                update outbox_entrega set status_entrega = 'PENDENTE', proxima_tentativa_em = :agora,
                    claim_id = null, claim_expira_em = null where message_id = :messageId
                """)
                .param("agora", Timestamp.from(AGORA))
                .param("messageId", messageId)
                .update();
    }

    private void forcarTentativas(UUID messageId, int tentativas) {
        appJdbcClient.sql("update outbox_entrega set tentativas = :tentativas where message_id = :messageId")
                .param("tentativas", tentativas)
                .param("messageId", messageId)
                .update();
    }

    private Integer tentativasDe(UUID messageId) {
        return appJdbcClient.sql("select tentativas from outbox_entrega where message_id = :messageId")
                .param("messageId", messageId).query(Integer.class).single();
    }

    private String statusEntregaDe(UUID messageId) {
        return appJdbcClient.sql("select status_entrega from outbox_entrega where message_id = :messageId")
                .param("messageId", messageId).query(String.class).single();
    }

    private UUID claimIdDe(UUID messageId) {
        return appJdbcClient.sql("select claim_id from outbox_entrega where message_id = :messageId")
                .param("messageId", messageId).query(UUID.class).optional().orElse(null);
    }

    private Instant proximaTentativaDe(UUID messageId) {
        Timestamp ts = appJdbcClient.sql("select proxima_tentativa_em from outbox_entrega where message_id = :messageId")
                .param("messageId", messageId).query(Timestamp.class).single();
        return ts.toInstant();
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
}
