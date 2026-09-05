package com.fkmanager360.credito.smoke;

import com.fkmanager360.credito.adapter.out.legacy.ConsultaStatusEfetivacaoAclAdapter;
import com.fkmanager360.credito.adapter.out.legacy.CreditoLegadoAclAdapter;
import com.fkmanager360.credito.adapter.out.legacy.EfetivacaoLegadoAclAdapter;
import com.fkmanager360.credito.application.port.out.IntencaoEfetivacao;
import com.fkmanager360.credito.application.port.out.ResultadoConsultaStatusCore;
import com.fkmanager360.credito.application.port.out.ResultadoInstrucaoCore;
import com.fkmanager360.credito.domain.ClassificacaoRiscoCreditoBase;
import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.CorrelationId;
import com.fkmanager360.credito.domain.DadosCreditoCore;
import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.LimiteChequeEspecialVigente;
import com.fkmanager360.credito.domain.LimiteSolicitado;
import com.fkmanager360.credito.domain.MotivoFalhaEfetivacao;
import com.fkmanager360.credito.domain.ProtocoloCore;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            // #0004: o control plane de cenarios de efetivacao (ADR-0018) so existe nos profiles
            // local/demo/test -- sem isto, /control-plane/efetivacoes/** nao estaria mapeado.
            .withEnv("SPRING_PROFILES_ACTIVE", "test")
            // #0004: espera por MENSAGEM DE LOG (lida via API do Docker), nao por HTTP contra a
            // porta publicada -- neste ambiente de execucao (Docker-outside-of-Docker, ver
            // comentario em baseUrlDoSimulador()), o mapeamento de porta publicada mostrou-se
            // inalcancavel a partir de outro container mesmo com o container saudavel, enquanto a
            // leitura de log via API nao depende dessa rota de rede.
            .waitingFor(Wait.forLogMessage(".*Started SimuladorCoreLegadoApplication.*\\n", 1))
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

    /**
     * #0004: o test runner deste modulo tambem roda dentro de um container
     * (Docker-outside-of-Docker, socket montado -- memoria de projeto "maven-via-docker"). Neste
     * ambiente, o mapeamento de porta publicada (host:portaMapeada, via gateway da bridge) mostrou-se
     * consistentemente inalcancavel a partir de outro container, mesmo com o container saudavel e a
     * porta corretamente publicada (verificado empiricamente contra o Postgres de
     * {@code JpaSolicitacoesAumentoLimiteAdapterTest}) -- o proprio IP do container na rede bridge,
     * na porta interna, e sempre alcancavel. Usar o IP do container evita depender do proxy de
     * porta publicada do Docker Desktop.
     */
    private static String baseUrlDoSimulador() {
        String enderecoContainer = SIMULADOR.getContainerInfo().getNetworkSettings().getNetworks().get("bridge").getIpAddress();
        return "http://" + enderecoContainer + ":8090";
    }

    private CreditoLegadoAclAdapter adapterContraOSimuladorReal() {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrlDoSimulador())
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

    // --- Efetivacao (plano #0004, secao 10 -- S5) ---------------------------------------------

    private EfetivacaoLegadoAclAdapter adapterEfetivacaoContraOSimuladorReal() {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrlDoSimulador())
                .requestFactory(requestFactory)
                .build();

        return new EfetivacaoLegadoAclAdapter(restClient);
    }

    /** Chama o control plane (ADR-0018), ativo apenas porque o container sobe com profile {@code test}. */
    private RestClient controlPlaneRestClient() {
        return RestClient.builder()
                .baseUrl(baseUrlDoSimulador())
                .build();
    }

    private static IntencaoEfetivacao intencao(String contaId, long limiteEsperado, long limiteSolicitado) {
        return new IntencaoEfetivacao(
                new EfetivacaoId(UUID.randomUUID()), UUID.randomUUID(), new ContaId(contaId),
                new LimiteChequeEspecialVigente(limiteEsperado), new LimiteSolicitado(limiteSolicitado),
                new CorrelationId(UUID.randomUUID()));
    }

    @Test
    void efetivacao_dedupePorEfetivacaoId_mesmoPayload_devolveOMesmoProtocolo() {
        // Conta 10002: vigente real 120000 centavos no simulador.
        IntencaoEfetivacao intencaoUnica = intencao("10002", 120_000, 130_000);
        EfetivacaoLegadoAclAdapter adapter = adapterEfetivacaoContraOSimuladorReal();

        ResultadoInstrucaoCore primeiro = adapter.entregar(intencaoUnica);
        ResultadoInstrucaoCore segundo = adapter.entregar(intencaoUnica);

        assertThat(primeiro).isInstanceOf(ResultadoInstrucaoCore.Aceite.class);
        assertThat(segundo).isEqualTo(primeiro);
    }

    @Test
    void efetivacao_mesmoEfetivacaoId_payloadIncompativel_eIndeterminado_naoOperacaoNova() {
        EfetivacaoLegadoAclAdapter adapter = adapterEfetivacaoContraOSimuladorReal();
        EfetivacaoId efetivacaoId = new EfetivacaoId(UUID.randomUUID());
        UUID messageId = UUID.randomUUID();
        // Conta 10003: vigente real 1000000 centavos no simulador.
        ContaId contaId = new ContaId("10003");
        CorrelationId correlationId = new CorrelationId(UUID.randomUUID());

        IntencaoEfetivacao primeira = new IntencaoEfetivacao(
                efetivacaoId, messageId, contaId, new LimiteChequeEspecialVigente(1_000_000),
                new LimiteSolicitado(1_100_000), correlationId);
        IntencaoEfetivacao comValorDiferente = new IntencaoEfetivacao(
                efetivacaoId, messageId, contaId, new LimiteChequeEspecialVigente(1_000_000),
                new LimiteSolicitado(1_200_000), correlationId);

        assertThat(adapter.entregar(primeira)).isInstanceOf(ResultadoInstrucaoCore.Aceite.class);
        assertThat(adapter.entregar(comValorDiferente)).isInstanceOf(ResultadoInstrucaoCore.RespostaIndeterminada.class);
    }

    @Test
    void efetivacao_limiteVigenteEsperadoDivergente_eFalhaDefinitiva() {
        // Conta 10004: vigente real 250000 centavos no simulador -- a instrucao espera outro valor.
        ResultadoInstrucaoCore resultado =
                adapterEfetivacaoContraOSimuladorReal().entregar(intencao("10004", 999_999, 1_000_000));

        assertThat(resultado).isEqualTo(new ResultadoInstrucaoCore.FalhaDefinitiva(MotivoFalhaEfetivacao.LIMITE_VIGENTE_DIVERGENTE));
    }

    @Test
    void efetivacao_perderAceite_reenvioComMesmoEfetivacaoIdRecuperaOMesmoProtocolo() {
        controlPlaneRestClient().post().uri("/control-plane/efetivacoes/0000010006/perder-aceite")
                .retrieve().toBodilessEntity();

        // Conta 10006: vigente real 0 no simulador.
        IntencaoEfetivacao intencaoUnica = intencao("10006", 0, 10_000);
        EfetivacaoLegadoAclAdapter adapter = adapterEfetivacaoContraOSimuladorReal();

        ResultadoInstrucaoCore primeiraTentativa = adapter.entregar(intencaoUnica);
        ResultadoInstrucaoCore segundaTentativa = adapter.entregar(intencaoUnica);

        assertThat(primeiraTentativa).isInstanceOf(ResultadoInstrucaoCore.FalhaTransitoria.class);
        assertThat(segundaTentativa).isInstanceOf(ResultadoInstrucaoCore.Aceite.class);
    }

    @Test
    void efetivacao_indisponivel_eTransitoria() {
        controlPlaneRestClient().post().uri("/control-plane/efetivacoes/0000010007/indisponivel?vezes=1")
                .retrieve().toBodilessEntity();

        // Conta 10007: vigente real 750000 centavos no simulador.
        ResultadoInstrucaoCore resultado =
                adapterEfetivacaoContraOSimuladorReal().entregar(intencao("10007", 750_000, 800_000));

        assertThat(resultado).isInstanceOf(ResultadoInstrucaoCore.FalhaTransitoria.class);
    }

    @Test
    void consultadoEm_vemDoRelogioDaPlataforma_naoDaDataDeAtualizacaoDoHost() {
        // A conta 10005 tem datAtuLim de 2024 no dataset do simulador; a captura e de 2026.
        DadosCreditoCore dados = adapterContraOSimuladorReal().consultar(new ContaId("10005")).orElseThrow();

        assertThat(dados.consultadoEm()).isEqualTo(AGORA);
    }

    // --- Processamento assincrono e consulta-credito reflete o novo vigente (#0005, AC1) -------

    /**
     * {@code simulador.callback.url} vazio neste container standalone (sem servico-credito de pe)
     * -- o disparo do callback e um no-op logado, mas a MUTACAO de {@code ContasLegadoStore} e
     * independente disso e acontece do mesmo jeito, apos o atraso configurado
     * ({@code simulador.callback.atraso}, default ~0.5s). O poll curto e o padrao ja estabelecido
     * neste modulo para aguardar efeito assincrono sem {@code Thread.sleep} de duracao fixa.
     */
    @Test
    void aceite_processaAssincronamente_eConsultaCreditoPassaARefletirONovoVigente() {
        // Conta 10008: vigente real 180000 centavos no simulador.
        IntencaoEfetivacao intencao = intencao("10008", 180_000, 250_000);

        ResultadoInstrucaoCore resultado = adapterEfetivacaoContraOSimuladorReal().entregar(intencao);
        assertThat(resultado).isInstanceOf(ResultadoInstrucaoCore.Aceite.class);

        pollarAteVigenteRefletirONovoValor(new ContaId("10008"), 250_000);
    }

    /** Poll curto sem {@code Thread.sleep} de duracao fixa -- mesmo espirito de {@code expect.poll} no e2e. */
    private void pollarAteVigenteRefletirONovoValor(ContaId contaId, long vigenteEsperadoCentavos) {
        Instant limite = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(limite)) {
            DadosCreditoCore dados = adapterContraOSimuladorReal().consultar(contaId).orElseThrow();
            if (dados.limiteChequeEspecialVigente().centavos() == vigenteEsperadoCentavos) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        assertThat(adapterContraOSimuladorReal().consultar(contaId).orElseThrow().limiteChequeEspecialVigente().centavos())
                .isEqualTo(vigenteEsperadoCentavos);
    }

    // --- Consulta de status por protocolo e por EfetivacaoId (#0006, S5) -----------------------

    private ConsultaStatusEfetivacaoAclAdapter adapterConsultaStatusContraOSimuladorReal() {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrlDoSimulador())
                .requestFactory(requestFactory)
                .build();

        return new ConsultaStatusEfetivacaoAclAdapter(restClient);
    }

    @Test
    void consultaStatus_porEfetivacaoId_emProcessamentoDepoisProcessada_comLimiteEfetivado() {
        // Conta 10001: vigente real 500000 centavos no simulador (nao usada por outro teste desta suite).
        IntencaoEfetivacao intencao = intencao("10001", 500_000, 550_000);
        EfetivacaoId efetivacaoId = intencao.efetivacaoId();

        ResultadoInstrucaoCore aceite = adapterEfetivacaoContraOSimuladorReal().entregar(intencao);
        assertThat(aceite).isInstanceOf(ResultadoInstrucaoCore.Aceite.class);

        ResultadoConsultaStatusCore statusInicial = adapterConsultaStatusContraOSimuladorReal().consultarPorEfetivacaoId(efetivacaoId);
        assertThat(statusInicial).isInstanceOf(ResultadoConsultaStatusCore.EmProcessamento.class);

        ResultadoConsultaStatusCore statusFinal = pollarStatusAteProcessada(
                () -> adapterConsultaStatusContraOSimuladorReal().consultarPorEfetivacaoId(efetivacaoId));
        assertThat(statusFinal).isEqualTo(new ResultadoConsultaStatusCore.Efetivada(
                ((ResultadoInstrucaoCore.Aceite) aceite).protocoloCore(), 550_000L));
    }

    @Test
    void consultaStatus_porProtocolo_devolveOMesmoDesfechoQuePorEfetivacaoId() {
        // Conta 10002 ja e usada por outro teste desta suite para dedupe -- uma nova aqui evita
        // interferencia (o dedupe e por idEft, nao por conta, mas o vigente real de cada conta e
        // fixo, entao reusar valida o mesmo dataset).
        IntencaoEfetivacao intencao = intencao("10002", 120_000, 140_000);

        ResultadoInstrucaoCore aceite = adapterEfetivacaoContraOSimuladorReal().entregar(intencao);
        ProtocoloCore protocolo = ((ResultadoInstrucaoCore.Aceite) aceite).protocoloCore();

        ResultadoConsultaStatusCore statusFinal = pollarStatusAteProcessada(
                () -> adapterConsultaStatusContraOSimuladorReal().consultarPorProtocolo(protocolo));
        assertThat(statusFinal).isEqualTo(new ResultadoConsultaStatusCore.Efetivada(protocolo, 140_000L));
    }

    @Test
    void consultaStatus_efetivacaoIdDesconhecido_devolveDesconhecida() {
        ResultadoConsultaStatusCore status =
                adapterConsultaStatusContraOSimuladorReal().consultarPorEfetivacaoId(new EfetivacaoId(UUID.randomUUID()));

        assertThat(status).isInstanceOf(ResultadoConsultaStatusCore.Desconhecida.class);
    }

    @Test
    void consultaStatus_protocoloDesconhecido_devolveDesconhecida() {
        ResultadoConsultaStatusCore status =
                adapterConsultaStatusContraOSimuladorReal().consultarPorProtocolo(new ProtocoloCore("999999999999"));

        assertThat(status).isInstanceOf(ResultadoConsultaStatusCore.Desconhecida.class);
    }

    /** Poll curto sem {@code Thread.sleep} de duracao fixa -- mesmo espirito de {@code expect.poll} no e2e. */
    private ResultadoConsultaStatusCore pollarStatusAteProcessada(java.util.function.Supplier<ResultadoConsultaStatusCore> consulta) {
        Instant limite = Instant.now().plusSeconds(5);
        ResultadoConsultaStatusCore ultimo;
        do {
            ultimo = consulta.get();
            if (ultimo instanceof ResultadoConsultaStatusCore.Efetivada) {
                return ultimo;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        } while (Instant.now().isBefore(limite));
        return ultimo;
    }

    // --- Control plane de callback (#0006, S5): suprimir e suspender ----------------------------

    @Test
    void suprimirCallback_processaNormalmenteEConsultaDeStatusReflete_disparoUnico() {
        // Conta 20001 (carteira B, vigente real 450000): nao usada por outro teste desta suite.
        controlPlaneRestClient().post().uri("/control-plane/efetivacoes/0000020001/suprimir-callback")
                .retrieve().toBodilessEntity();
        IntencaoEfetivacao intencao = intencao("20001", 450_000, 500_000);

        ResultadoInstrucaoCore aceite = adapterEfetivacaoContraOSimuladorReal().entregar(intencao);
        assertThat(aceite).isInstanceOf(ResultadoInstrucaoCore.Aceite.class);

        // O processamento acontece normalmente (limite muda, desfecho registrado) mesmo com o
        // callback suprimido -- e exatamente essa convergencia que a reconciliacao explora (AC12).
        ResultadoConsultaStatusCore statusFinal = pollarStatusAteProcessada(
                () -> adapterConsultaStatusContraOSimuladorReal().consultarPorEfetivacaoId(intencao.efetivacaoId()));
        assertThat(statusFinal).isEqualTo(new ResultadoConsultaStatusCore.Efetivada(
                ((ResultadoInstrucaoCore.Aceite) aceite).protocoloCore(), 500_000L));
    }

    @Test
    void suspenderProcessamento_permaneceEmProcessamentoAteLiberar_entaoProcessaEEfetiva() throws InterruptedException {
        controlPlaneRestClient().post().uri("/control-plane/efetivacoes/0000020002/suspender-processamento")
                .retrieve().toBodilessEntity();

        // Conta 20002: vigente real 600000 centavos no simulador.
        IntencaoEfetivacao intencao = intencao("20002", 600_000, 650_000);
        ResultadoInstrucaoCore aceite = adapterEfetivacaoContraOSimuladorReal().entregar(intencao);
        assertThat(aceite).isInstanceOf(ResultadoInstrucaoCore.Aceite.class);

        // Aguarda alem do atraso normal de processamento (~0.5s) e confirma que NADA processou --
        // suspenso de verdade, nao so atrasado.
        Thread.sleep(1500);
        ResultadoConsultaStatusCore statusSuspenso =
                adapterConsultaStatusContraOSimuladorReal().consultarPorEfetivacaoId(intencao.efetivacaoId());
        assertThat(statusSuspenso).isInstanceOf(ResultadoConsultaStatusCore.EmProcessamento.class);

        controlPlaneRestClient().post().uri("/control-plane/efetivacoes/0000020002/liberar")
                .retrieve().toBodilessEntity();

        ResultadoConsultaStatusCore statusFinal = pollarStatusAteProcessada(
                () -> adapterConsultaStatusContraOSimuladorReal().consultarPorEfetivacaoId(intencao.efetivacaoId()));
        assertThat(statusFinal).isEqualTo(new ResultadoConsultaStatusCore.Efetivada(
                ((ResultadoInstrucaoCore.Aceite) aceite).protocoloCore(), 650_000L));
    }

    @Test
    void liberarProcessamento_semPendenciaSuspensa_e404() {
        assertThatThrownBy(() -> controlPlaneRestClient().post().uri("/control-plane/efetivacoes/0000010001/liberar")
                .retrieve()
                .toBodilessEntity())
                .isInstanceOfSatisfying(org.springframework.web.client.HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }
}
