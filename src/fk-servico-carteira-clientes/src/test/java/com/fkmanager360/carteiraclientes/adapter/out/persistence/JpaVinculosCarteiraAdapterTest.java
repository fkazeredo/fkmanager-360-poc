package com.fkmanager360.carteiraclientes.adapter.out.persistence;

import com.fkmanager360.carteiraclientes.adapter.out.persistence.entity.VinculoCarteiraEntity;
import com.fkmanager360.carteiraclientes.adapter.out.persistence.repository.VinculoCarteiraRepository;
import com.fkmanager360.carteiraclientes.domain.ClienteId;
import com.fkmanager360.carteiraclientes.domain.GerenteId;
import com.fkmanager360.carteiraclientes.domain.PageResult;
import com.fkmanager360.carteiraclientes.domain.Pagination;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S3 (ADR-0018): comportamento de persistencia provado contra PostgreSQL real via
 * Testcontainers, aplicando os proprios scripts de {@code src/main/resources/db/migration} deste
 * modulo (migracao embutida, ADR-0014). H2 nao substitui este teste.
 *
 * <p><b>Diferenca deliberada em relacao ao teste anterior ao refactor JPA</b>
 * ({@code PostgresVinculosCarteiraAdapterTest}): aquele rodava Flyway e o cliente de teste com o
 * MESMO superusuario do container, o que nunca provava segregacao real de privilegio. Aqui,
 * DEPOIS de o container subir, conectamos com o superusuario apenas para criar
 * {@code carteira_migrator} e {@code carteira_app} exatamente como
 * {@code infra/postgres-init/01-carteira-clientes.sh} faria (mesmo padrao ja usado por
 * {@code fk-servico-credito/.../PostgresSolicitacoesAumentoLimiteAdapterTest} -- duplicado aqui de
 * proposito, sem extrair codigo comum entre modulos, ADR-0011); Flyway roda com
 * {@code carteira_migrator}; e {@link #adapter}, usado por TODOS os testes funcionais abaixo,
 * roda exclusivamente com {@code carteira_app} -- inclusive o {@link EntityManagerFactory} do
 * Hibernate, provando {@code ddl-auto=validate} sob o mesmo privilegio de producao, nao sob um
 * superusuario que mascararia um problema de GRANT.
 */
@Testcontainers
class JpaVinculosCarteiraAdapterTest {

    private static final String MIGRATOR_USER = "carteira_migrator";
    private static final String MIGRATOR_PASSWORD = "migrator-teste-nao-usar-em-producao";
    private static final String APP_USER = "carteira_app";
    private static final String APP_PASSWORD = "app-teste-nao-usar-em-producao";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static String carteiraJdbcUrl;
    private static EntityManagerFactory entityManagerFactory;
    private static JpaVinculosCarteiraAdapter adapter;

    @BeforeAll
    static void provisionarPapeisMigrarESubirAdapterComCredencialDeApp() throws SQLException {
        carteiraJdbcUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/carteira_clientes_db";

        // Espelha infra/postgres-init/01-carteira-clientes.sh literalmente, so que em Java: o
        // superusuario do Testcontainers e usado APENAS aqui, para criar os dois papeis e o
        // database -- nunca pelos testes funcionais abaixo.
        try (Connection superusuario = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = superusuario.createStatement()) {
            stmt.execute("create role " + MIGRATOR_USER + " with login password '" + MIGRATOR_PASSWORD + "'");
            stmt.execute("create role " + APP_USER + " with login password '" + APP_PASSWORD + "'");
            stmt.execute("create database carteira_clientes_db owner " + MIGRATOR_USER);
        }

        try (Connection superusuarioNoDb = DriverManager.getConnection(
                carteiraJdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = superusuarioNoDb.createStatement()) {
            stmt.execute("alter schema public owner to " + MIGRATOR_USER);
            stmt.execute("grant connect on database carteira_clientes_db to " + APP_USER);
            stmt.execute("grant usage on schema public to " + APP_USER);
            stmt.execute("alter default privileges for role " + MIGRATOR_USER + " in schema public "
                    + "grant select, insert, update, delete on tables to " + APP_USER);
            stmt.execute("alter default privileges for role " + MIGRATOR_USER + " in schema public "
                    + "grant usage, select on sequences to " + APP_USER);
        }

        // Migracao embutida (ADR-0014, emenda 2026-09-02): SO com a credencial de DDL.
        Flyway.configure()
                .dataSource(carteiraJdbcUrl, MIGRATOR_USER, MIGRATOR_PASSWORD)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // A partir daqui, TUDO que os testes funcionais usam roda com a credencial de DML.
        entityManagerFactory = construirSessionFactory(APP_USER, APP_PASSWORD);

        // SharedEntityManagerCreator e o mesmo mecanismo que Spring usa por baixo de
        // JpaRepositoryFactoryBean/@PersistenceContext: o proxy devolvido abre e fecha um
        // EntityManager de fato a cada chamada sem transacao ja em andamento na thread corrente,
        // o que o torna seguro para ser compartilhado por um unico adapter sob concorrencia --
        // sem isso, um unico EntityManager (nao thread-safe) quebraria o teste de concorrencia
        // abaixo.
        EntityManager entityManagerCompartilhado = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        VinculoCarteiraRepository repository =
                new JpaRepositoryFactory(entityManagerCompartilhado).getRepository(VinculoCarteiraRepository.class);
        adapter = new JpaVinculosCarteiraAdapter(repository);
    }

    @AfterAll
    static void fecharEntityManagerFactory() {
        entityManagerFactory.close();
    }

    /**
     * Monta um {@link SessionFactory} (que e um {@link EntityManagerFactory}) programaticamente,
     * sem contexto Spring nem {@code persistence.xml}. {@code hibernate.hbm2ddl.auto=validate} e
     * o equivalente nativo de {@code spring.jpa.hibernate.ddl-auto=validate} (application.yml).
     */
    private static SessionFactory construirSessionFactory(String usuario, String senha) {
        return new Configuration()
                .addAnnotatedClass(VinculoCarteiraEntity.class)
                .setProperty(AvailableSettings.JAKARTA_JDBC_URL, carteiraJdbcUrl)
                .setProperty(AvailableSettings.JAKARTA_JDBC_USER, usuario)
                .setProperty(AvailableSettings.JAKARTA_JDBC_PASSWORD, senha)
                .setProperty(AvailableSettings.HBM2DDL_AUTO, "validate")
                .buildSessionFactory();
    }

    // ---------------------------------------------------------------------------------------
    // Guardrail de privilegios reais: migrator tem DDL, app nao tem DDL, Hibernate valida sob a
    // credencial de app (ADR-0014, emenda 2026-09-02).
    // ---------------------------------------------------------------------------------------

    @Test
    void migrator_temPrivilegioDeDdl_conseguindoCriarEDerrubarTabela() throws SQLException {
        try (Connection conMigrator = DriverManager.getConnection(carteiraJdbcUrl, MIGRATOR_USER, MIGRATOR_PASSWORD);
             Statement stmt = conMigrator.createStatement()) {
            stmt.execute("create table zz_privilegio_smoke_migrator (id int)");
            stmt.execute("drop table zz_privilegio_smoke_migrator");
        }
    }

    @Test
    void app_naoTemPrivilegioDeDdl_falhaAoTentarCriarTabela() {
        assertThatThrownBy(() -> {
            try (Connection conApp = DriverManager.getConnection(carteiraJdbcUrl, APP_USER, APP_PASSWORD);
                 Statement stmt = conApp.createStatement()) {
                stmt.execute("create table zz_privilegio_smoke_app (id int)");
            }
        }).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }

    @Test
    void app_naoTemPrivilegioDeDdl_falhaAoTentarAlterarTabelaExistente() {
        assertThatThrownBy(() -> {
            try (Connection conApp = DriverManager.getConnection(carteiraJdbcUrl, APP_USER, APP_PASSWORD);
                 Statement stmt = conApp.createStatement()) {
                stmt.execute("alter table vinculo_carteira add column zz_smoke varchar(1)");
            }
        }).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }

    /**
     * A prova de que o mapeamento de {@link VinculoCarteiraEntity} bate com a migration: um
     * {@link SessionFactory} efemero, dedicado so a esta asserção, sob a credencial de app. Se
     * {@code @Table}/{@code @Column}/{@code uniqueConstraints} da entity divergissem do que
     * {@code V1__criar_vinculo_carteira.sql} de fato criou, {@code buildSessionFactory()} abaixo
     * lancaria {@code SchemaManagementException} e este teste falharia -- exatamente o que
     * {@code ddl-auto=validate} promete em producao (application.yml).
     */
    @Test
    void hibernateDdlAutoValidate_passaComACredencialDeApp_contraOSchemaJaMigrado() {
        try (SessionFactory sessionFactoryEfemero = construirSessionFactory(APP_USER, APP_PASSWORD)) {
            assertThat(sessionFactoryEfemero.isOpen()).isTrue();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Os comportamentos ja provados por PostgresVinculosCarteiraAdapterTest, preservados
    // integralmente sob a nova implementacao JPA.
    // ---------------------------------------------------------------------------------------

    @Test
    void buscarPagina_devolveSomenteClientesDaquelaCarteira_naOrdemDeInsercao() {
        PageResult<ClienteId> pagina = adapter.findPage(new GerenteId("gerente.a"), new Pagination(0, 5));

        assertThat(pagina.items()).extracting(ClienteId::valor)
                .containsExactly("1", "2", "3", "4", "5");
        assertThat(pagina.totalElements()).isEqualTo(7);
        assertThat(pagina.totalPages()).isEqualTo(2);
    }

    @Test
    void buscarPagina_segundaPagina_devolveOsRestantes() {
        PageResult<ClienteId> pagina = adapter.findPage(new GerenteId("gerente.a"), new Pagination(1, 5));

        assertThat(pagina.items()).extracting(ClienteId::valor).containsExactly("6", "7");
        assertThat(pagina.totalElements()).isEqualTo(7);
    }

    @Test
    void buscarPagina_nuncaMisturaClientesDeOutraCarteira() {
        PageResult<ClienteId> paginaA = adapter.findPage(new GerenteId("gerente.a"), new Pagination(0, 20));
        PageResult<ClienteId> paginaB = adapter.findPage(new GerenteId("gerente.b"), new Pagination(0, 20));

        assertThat(paginaA.items()).extracting(ClienteId::valor)
                .doesNotContainAnyElementsOf(paginaB.items().stream().map(ClienteId::valor).toList());
        assertThat(paginaB.items()).extracting(ClienteId::valor).containsExactly("101", "102", "103");
    }

    @Test
    void buscarPagina_gerenteSemCarteira_devolvePaginaVazia() {
        PageResult<ClienteId> pagina = adapter.findPage(new GerenteId("gerente.sem.carteira"), Pagination.ofDefault());

        assertThat(pagina.items()).isEmpty();
        assertThat(pagina.totalElements()).isZero();
    }

    /**
     * A partir de #0002 esta consulta deixou de ser mais uma leitura: ela e o guard que precede
     * toda chamada ao CoreLegado (AC23). Um falso positivo aqui autorizaria um atendimento
     * indevido; um falso negativo bloquearia atendimento legitimo. Por isso ela e provada contra
     * PostgreSQL real, e nao contra um fake de repositorio.
     */
    @Test
    void existeVinculo_gerenteComVinculoAtualComAqueleCliente_eVerdadeiro() {
        assertThat(adapter.existeVinculo(new GerenteId("gerente.a"), new ClienteId("1"))).isTrue();
        assertThat(adapter.existeVinculo(new GerenteId("gerente.b"), new ClienteId("101"))).isTrue();
    }

    @Test
    void existeVinculo_gerenteSemVinculoComAqueleCliente_eFalso() {
        // O Cliente "101" existe e tem carteira -- so nao a do gerente.a. A ausencia e do vinculo,
        // nao do Cliente: e exatamente a distincao que faz a segregacao ser real.
        assertThat(adapter.existeVinculo(new GerenteId("gerente.a"), new ClienteId("101"))).isFalse();
        assertThat(adapter.existeVinculo(new GerenteId("gerente.b"), new ClienteId("1"))).isFalse();
    }

    @Test
    void existeVinculo_clienteQueExisteNoCoreMasNaoTemCarteira_eFalso() {
        // Cliente "999" e semeado no simulador e deliberadamente sem vinculo aqui: existir no
        // Core, isolado, nao concede acesso a ninguem.
        assertThat(adapter.existeVinculo(new GerenteId("gerente.a"), new ClienteId("999"))).isFalse();
    }

    @Test
    void existeVinculo_identificadorInexistente_eFalso_semLancar() {
        assertThat(adapter.existeVinculo(new GerenteId("gerente.inexistente"), new ClienteId("1"))).isFalse();
        assertThat(adapter.existeVinculo(new GerenteId("gerente.a"), new ClienteId("8888888888"))).isFalse();
    }

    @Test
    void buscarPagina_sobConcorrenciaReal_naoCorrompeContagemNemOrdenacao() throws Exception {
        // Nao ha escrita concorrente neste ticket (a associacao e semeada por migration), mas a
        // leitura concorrente sobre o mesmo adapter precisa continuar consistente -- prova que o
        // adapter e seguro para o uso concorrente que o Compose (varias requisicoes simultaneas
        // ao servico) de fato produz. So e seguro porque o EntityManager por tras do repository e
        // o proxy compartilhado do Spring (ver construirSessionFactory/@BeforeAll), nao uma
        // unica instancia de persistencia mutavel.
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            var chamadas = IntStream.range(0, 20)
                    .mapToObj(i -> CompletableFuture.supplyAsync(
                            () -> adapter.findPage(new GerenteId("gerente.a"), new Pagination(0, 5)), executor))
                    .toList();

            for (var chamada : chamadas) {
                PageResult<ClienteId> pagina = chamada.get(10, TimeUnit.SECONDS);
                assertThat(pagina.items()).extracting(ClienteId::valor)
                        .containsExactly("1", "2", "3", "4", "5");
                assertThat(pagina.totalElements()).isEqualTo(7);
            }
        } finally {
            executor.shutdown();
        }
    }
}
