package com.fkmanager360.carteiraclientes.smoke;

import com.fkmanager360.carteiraclientes.adapter.out.legacy.ClienteLegadoAclAdapter;
import com.fkmanager360.carteiraclientes.adapter.out.legacy.ContaLegadoAclAdapter;
import com.fkmanager360.carteiraclientes.domain.ClienteId;
import com.fkmanager360.carteiraclientes.domain.ContaCorrente;
import com.fkmanager360.carteiraclientes.domain.DadosMestresCliente;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5: conjunto pequeno contra o simulador-core-legado real (nao mockado), construido a partir do
 * seu proprio Dockerfile -- detecta deriva entre esta ACL e o contrato que o simulador de fato
 * implementa. Nao duplica a matriz patologica de S4.
 *
 * <p>A imagem e construida pelo Docker CLI (nao pelo {@code ImageFromDockerfile} do
 * Testcontainers): o empacotamento de build context em Java nao incluiu o {@code mvnw} do
 * reactor de forma confiavel, enquanto o {@code docker build} direto contra a raiz do
 * repositorio funciona -- e o mesmo comando que F7 usara no Compose.
 */
@Testcontainers
class SimuladorCoreLegadoSmokeTest {

    private static final String IMAGEM = "fkmanager360/simulador-core-legado:smoke-test";
    private static final Path RAIZ_DO_REPOSITORIO =
            Paths.get("").toAbsolutePath().resolve("../..").normalize();

    static {
        // Bloco static (nao @BeforeAll): precisa terminar antes que a inicializacao do campo
        // @Container abaixo tente subir um container a partir da imagem.
        try {
            construirImagemViaDockerCli();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Falha ao construir a imagem de " + IMAGEM + " para o smoke test", e);
        }
    }

    @Container
    static final GenericContainer<?> SIMULADOR = new GenericContainer<>(DockerImageName.parse(IMAGEM))
            .withExposedPorts(8090)
            .waitingFor(Wait.forHttp("/actuator/health").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(2));

    private static void construirImagemViaDockerCli() throws IOException, InterruptedException {
        Process build = new ProcessBuilder(
                "docker", "build",
                "-f", "src/fk-simulador-core-legado/Dockerfile",
                "-t", IMAGEM,
                ".")
                .directory(RAIZ_DO_REPOSITORIO.toFile())
                .inheritIO()
                .start();

        boolean terminou = build.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
        if (!terminou || build.exitValue() != 0) {
            throw new IllegalStateException("docker build saiu com falha para " + IMAGEM);
        }
    }

    private RestClient restClientContraOSimuladorReal() {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        return RestClient.builder()
                .baseUrl("http://" + SIMULADOR.getHost() + ":" + SIMULADOR.getMappedPort(8090))
                .requestFactory(requestFactory)
                .build();
    }

    private ClienteLegadoAclAdapter adapterContraOSimuladorReal() {
        return new ClienteLegadoAclAdapter(restClientContraOSimuladorReal());
    }

    private ContaLegadoAclAdapter adapterDeContasContraOSimuladorReal() {
        return new ContaLegadoAclAdapter(restClientContraOSimuladorReal());
    }

    @Test
    void clienteExistente_eResolvidoPeloSimuladorReal() {
        var adapter = adapterContraOSimuladorReal();

        Map<ClienteId, DadosMestresCliente> resultado = adapter.buscarDadosMestres(List.of(new ClienteId("1")));

        assertThat(resultado).containsKey(new ClienteId("1"));
        assertThat(resultado.get(new ClienteId("1")).nome()).isEqualTo("ANA BEATRIZ SOUZA");
    }

    @Test
    void clienteInexistente_ficaAusenteDoResultado_semErro() {
        var adapter = adapterContraOSimuladorReal();

        Map<ClienteId, DadosMestresCliente> resultado = adapter.buscarDadosMestres(List.of(new ClienteId("123456")));

        assertThat(resultado).isEmpty();
    }

    @Test
    void loteMisto_resolveCadaClienteIndependentemente() {
        var adapter = adapterContraOSimuladorReal();

        Map<ClienteId, DadosMestresCliente> resultado = adapter.buscarDadosMestres(
                List.of(new ClienteId("1"), new ClienteId("101"), new ClienteId("999999")));

        assertThat(resultado).containsOnlyKeys(new ClienteId("1"), new ClienteId("101"));
    }

    @Test
    void contasDoCliente_saoResolvidasPeloSimuladorReal() {
        var adapter = adapterDeContasContraOSimuladorReal();

        List<ContaCorrente> contas = adapter.buscarContasDoCliente(new ClienteId("1"));

        assertThat(contas).extracting(conta -> conta.contaId().valor()).containsExactly("10001", "10002");
        assertThat(contas).extracting(ContaCorrente::agencia).containsOnly("0001");
    }

    @Test
    void clienteSemConta_devolveListaVazia_contraOSimuladorReal() {
        var adapter = adapterDeContasContraOSimuladorReal();

        assertThat(adapter.buscarContasDoCliente(new ClienteId("123456"))).isEmpty();
    }
}
