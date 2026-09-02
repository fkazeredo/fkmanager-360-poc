package com.fkmanager360.carteiraclientes.adapter.out.persistence;

import com.fkmanager360.carteiraclientes.domain.ClienteId;
import com.fkmanager360.carteiraclientes.domain.GerenteId;
import com.fkmanager360.carteiraclientes.domain.PageResult;
import com.fkmanager360.carteiraclientes.domain.Pagination;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3: comportamento de persistencia provado contra PostgreSQL real, aplicando os proprios
 * scripts de {@code src/main/resources/db/migration} deste modulo (migracao embutida,
 * ADR-0014). H2 nao substitui este teste (ADR-0018).
 */
@Testcontainers
class PostgresVinculosCarteiraAdapterTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    static HikariDataSource dataSource;
    static PostgresVinculosCarteiraAdapter adapter;

    @BeforeAll
    static void migrarESubirDataSource() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(10);
        dataSource = new HikariDataSource(config);

        adapter = new PostgresVinculosCarteiraAdapter(JdbcClient.create(dataSource));
    }

    @AfterAll
    static void fecharDataSource() {
        dataSource.close();
    }

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
        // leitura concorrente sobre o mesmo pool precisa continuar consistente -- prova que o
        // adapter e seguro para o uso concorrente que o Compose (varias requisicoes simultaneas
        // ao servico) de fato produz.
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
