package com.fkmanager360.carteiraclientes.adapters.saida.persistencia;

import com.fkmanager360.carteiraclientes.dominio.ClienteId;
import com.fkmanager360.carteiraclientes.dominio.GerenteId;
import com.fkmanager360.carteiraclientes.dominio.PaginaResultado;
import com.fkmanager360.carteiraclientes.dominio.Paginacao;
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
 * S3: comportamento de persistencia provado contra PostgreSQL real, aplicando as migrations
 * canonicas de {@code carteira-clientes-migracoes} (mesmo script, nao uma copia -- ver o recurso
 * de teste adicional no pom.xml). H2 nao substitui este teste (ADR-0018).
 */
@Testcontainers
class PostgresPortaVinculosCarteiraTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    static HikariDataSource dataSource;
    static PostgresPortaVinculosCarteira porta;

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

        porta = new PostgresPortaVinculosCarteira(JdbcClient.create(dataSource));
    }

    @AfterAll
    static void fecharDataSource() {
        dataSource.close();
    }

    @Test
    void buscarPagina_devolveSomenteClientesDaquelaCarteira_naOrdemDeInsercao() {
        PaginaResultado<ClienteId> pagina = porta.buscarPagina(new GerenteId("gerente.a"), new Paginacao(0, 5));

        assertThat(pagina.itens()).extracting(ClienteId::valor)
                .containsExactly("1", "2", "3", "4", "5");
        assertThat(pagina.totalElementos()).isEqualTo(7);
        assertThat(pagina.totalPaginas()).isEqualTo(2);
    }

    @Test
    void buscarPagina_segundaPagina_devolveOsRestantes() {
        PaginaResultado<ClienteId> pagina = porta.buscarPagina(new GerenteId("gerente.a"), new Paginacao(1, 5));

        assertThat(pagina.itens()).extracting(ClienteId::valor).containsExactly("6", "7");
        assertThat(pagina.totalElementos()).isEqualTo(7);
    }

    @Test
    void buscarPagina_nuncaMisturaClientesDeOutraCarteira() {
        PaginaResultado<ClienteId> paginaA = porta.buscarPagina(new GerenteId("gerente.a"), new Paginacao(0, 20));
        PaginaResultado<ClienteId> paginaB = porta.buscarPagina(new GerenteId("gerente.b"), new Paginacao(0, 20));

        assertThat(paginaA.itens()).extracting(ClienteId::valor)
                .doesNotContainAnyElementsOf(paginaB.itens().stream().map(ClienteId::valor).toList());
        assertThat(paginaB.itens()).extracting(ClienteId::valor).containsExactly("101", "102", "103");
    }

    @Test
    void buscarPagina_gerenteSemCarteira_devolvePaginaVazia() {
        PaginaResultado<ClienteId> pagina = porta.buscarPagina(new GerenteId("gerente.sem.carteira"), Paginacao.padrao());

        assertThat(pagina.itens()).isEmpty();
        assertThat(pagina.totalElementos()).isZero();
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
                            () -> porta.buscarPagina(new GerenteId("gerente.a"), new Paginacao(0, 5)), executor))
                    .toList();

            for (var chamada : chamadas) {
                PaginaResultado<ClienteId> pagina = chamada.get(10, TimeUnit.SECONDS);
                assertThat(pagina.itens()).extracting(ClienteId::valor)
                        .containsExactly("1", "2", "3", "4", "5");
                assertThat(pagina.totalElementos()).isEqualTo(7);
            }
        } finally {
            executor.shutdown();
        }
    }
}
