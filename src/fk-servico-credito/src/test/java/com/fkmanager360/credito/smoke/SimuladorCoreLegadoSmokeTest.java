package com.fkmanager360.credito.smoke;

import com.fkmanager360.credito.adapter.out.legacy.CreditoLegadoAclAdapter;
import com.fkmanager360.credito.domain.ClassificacaoRiscoCreditoBase;
import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.DadosCreditoCore;
import com.fkmanager360.credito.domain.SituacaoConta;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5: conjunto pequeno contra o simulador-core-legado real (nao mockado), construido a partir do
 * seu proprio Dockerfile -- detecta deriva entre esta ACL e o contrato que o simulador de fato
 * implementa. Nao duplica a matriz patologica de S4.
 *
 * <p>A imagem e construida pelo Docker CLI, e nao pelo {@code ImageFromDockerfile} do
 * Testcontainers, pelo mesmo motivo registrado no smoke de servico-carteira-clientes: o
 * empacotamento de build context em Java nao inclui o {@code mvnw} do reactor de forma confiavel.
 */
@Testcontainers
class SimuladorCoreLegadoSmokeTest {

    private static final String IMAGEM = "fkmanager360/simulador-core-legado:smoke-test";
    private static final Path RAIZ_DO_REPOSITORIO =
            Paths.get("").toAbsolutePath().resolve("../..").normalize();

    /** Relogio fixo: torna verificavel que consultadoEm nao vem de nada que o host devolveu. */
    private static final Instant AGORA = Instant.parse("2026-09-02T16:00:00Z");

    static {
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

    private CreditoLegadoAclAdapter adapterContraOSimuladorReal() {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        RestClient restClient = RestClient.builder()
                .baseUrl("http://" + SIMULADOR.getHost() + ":" + SIMULADOR.getMappedPort(8090))
                .requestFactory(requestFactory)
                .build();

        return new CreditoLegadoAclAdapter(restClient, Clock.fixed(AGORA, ZoneOffset.UTC));
    }

    @Test
    void dadosDeCreditoDaConta_saoTraduzidosDoContratoRealDoSimulador() {
        DadosCreditoCore dados = adapterContraOSimuladorReal().consultar(new ContaId("10001")).orElseThrow();

        assertThat(dados.limiteChequeEspecialVigente().centavos()).isEqualTo(500_000);
        assertThat(dados.situacaoConta()).isEqualTo(SituacaoConta.REGULAR);
        assertThat(dados.classificacaoRiscoCreditoBase()).isEqualTo(ClassificacaoRiscoCreditoBase.BAIXO);
        assertThat(dados.fonte()).isEqualTo("CoreLegado");
    }

    @Test
    void contaComSituacaoIrregularNoHost_eTraduzidaComoIrregular() {
        DadosCreditoCore dados = adapterContraOSimuladorReal().consultar(new ContaId("10005")).orElseThrow();

        assertThat(dados.situacaoConta()).isEqualTo(SituacaoConta.IRREGULAR);
    }

    @Test
    void contaDesconhecidaPeloSimulador_devolveAusencia_semErro() {
        assertThat(adapterContraOSimuladorReal().consultar(new ContaId("9999999")))
                .isEqualTo(Optional.empty());
    }

    @Test
    void consultadoEm_vemDoRelogioDaPlataforma_naoDaDataDeAtualizacaoDoHost() {
        // A conta 10005 tem datAtuLim de 2024 no dataset do simulador; a captura e de 2026.
        DadosCreditoCore dados = adapterContraOSimuladorReal().consultar(new ContaId("10005")).orElseThrow();

        assertThat(dados.consultadoEm()).isEqualTo(AGORA);
    }
}
