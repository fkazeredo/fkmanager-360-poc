package com.fkmanager360.credito.application.usecase;

import com.fkmanager360.credito.application.ClassificadorIdempotencia;
import com.fkmanager360.credito.application.ComandoSolicitacaoAumentoLimite;
import com.fkmanager360.credito.application.FingerprintCanonico;
import com.fkmanager360.credito.application.ResultadoSubmissao;
import com.fkmanager360.credito.application.port.out.ComandoInvalidoException;
import com.fkmanager360.credito.application.port.out.ContaNaoEncontradaException;
import com.fkmanager360.credito.application.port.out.DadosCreditoCorePort;
import com.fkmanager360.credito.application.port.out.DireitoDeAtendimentoPort;
import com.fkmanager360.credito.application.port.out.IdempotenciaFingerprintDivergenteException;
import com.fkmanager360.credito.application.port.out.LimiteSolicitadoNaoAumentaException;
import com.fkmanager360.credito.application.port.out.LimiteVigenteDesatualizadoException;
import com.fkmanager360.credito.application.port.out.NovaSolicitacaoAumentoLimite;
import com.fkmanager360.credito.application.port.out.RegistroIdempotencia;
import com.fkmanager360.credito.application.port.out.RegistroIdempotenciaPort;
import com.fkmanager360.credito.application.port.out.RegistroIdempotenteEncontrado;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroSolicitacao;
import com.fkmanager360.credito.application.port.out.SolicitacaoCriada;
import com.fkmanager360.credito.application.port.out.SolicitacaoNaoTerminalExistente;
import com.fkmanager360.credito.application.port.out.SolicitacaoNaoTerminalExistenteException;
import com.fkmanager360.credito.application.port.out.SolicitacoesAumentoLimitePort;
import com.fkmanager360.credito.domain.CanalManifestacao;
import com.fkmanager360.credito.domain.ContextoDecisaoCredito;
import com.fkmanager360.credito.domain.CorrelationId;
import com.fkmanager360.credito.domain.DadosCreditoCore;
import com.fkmanager360.credito.domain.LimiteSolicitado;
import com.fkmanager360.credito.domain.ManifestacaoCliente;
import com.fkmanager360.credito.domain.MotorDecisaoCredito;
import com.fkmanager360.credito.domain.OrigemSolicitacao;
import com.fkmanager360.credito.domain.VersaoPoliticaCredito;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Fase 0 (passos 2 a 12 do plano #0003) + Fase 1 (TX1) da submissao. O passo 1 -- header
 * {@code Idempotency-Key} ausente ou mal formado -- e responsabilidade da borda web (proxima
 * etapa): este caso de uso ja recebe uma {@link com.fkmanager360.credito.domain.IdempotencyKey}
 * bem-formada dentro do {@link ComandoSolicitacaoAumentoLimite}.
 *
 * <p><b>Ordem de execucao, normativa (spec, secao "Ordem de avaliacao na submissao"):</b>
 * fingerprint -&gt; pre-check de idempotencia (sem tocar rede) -&gt; validacoes puramente locais
 * (zero chamadas remotas se falharem) -&gt; autorizacao de recurso -&gt; consulta ao CoreLegado -&gt;
 * optimistic check (stale) -&gt; comparacao com o vigente -&gt; registro atomico (TX1, que tambem
 * absorve o pre-check de unicidade nao terminal via o resultado selado da porta) -&gt; Fase 2/3
 * (decisao), delegada a {@link DecidirSolicitacaoAumentoLimite} para que a solicitacao criada e a
 * decisao automatica cheguem na MESMA resposta HTTP (User Story 33).
 *
 * <p>O pre-check de unicidade nao terminal (passo 8 da spec) e deliberadamente NAO uma chamada de
 * leitura extra: a garantia real e do indice parcial do PostgreSQL (proxima etapa), e tentar
 * {@code registrar} diretamente e reagir ao {@link ResultadoRegistroSolicitacao} selado e
 * suficiente e mais simples do que duplicar a checagem em memoria.
 */
public class RegistrarSolicitacaoAumentoLimite {

    private final DireitoDeAtendimentoPort direitoDeAtendimento;
    private final DadosCreditoCorePort dadosCreditoCore;
    private final RegistroIdempotenciaPort registroIdempotencia;
    private final SolicitacoesAumentoLimitePort solicitacoes;
    private final MotorDecisaoCredito motorDecisaoCredito;
    private final DecidirSolicitacaoAumentoLimite decidirSolicitacaoAumentoLimite;

    public RegistrarSolicitacaoAumentoLimite(
            DireitoDeAtendimentoPort direitoDeAtendimento,
            DadosCreditoCorePort dadosCreditoCore,
            RegistroIdempotenciaPort registroIdempotencia,
            SolicitacoesAumentoLimitePort solicitacoes,
            MotorDecisaoCredito motorDecisaoCredito,
            DecidirSolicitacaoAumentoLimite decidirSolicitacaoAumentoLimite) {
        this.direitoDeAtendimento = direitoDeAtendimento;
        this.dadosCreditoCore = dadosCreditoCore;
        this.registroIdempotencia = registroIdempotencia;
        this.solicitacoes = solicitacoes;
        this.motorDecisaoCredito = motorDecisaoCredito;
        this.decidirSolicitacaoAumentoLimite = decidirSolicitacaoAumentoLimite;
    }

    public ResultadoSubmissao executar(ComandoSolicitacaoAumentoLimite comando, Instant agora) {
        Objects.requireNonNull(comando, "comando e obrigatorio");
        Objects.requireNonNull(agora, "agora e obrigatorio");

        String fingerprint = FingerprintCanonico.calcular(
                comando.clienteId(), comando.contaId(), comando.limiteSolicitado(),
                comando.limiteVigenteVisto(), comando.canalManifestacao(), comando.observacao());

        // Passo 5: pre-check de idempotencia, ANTES de qualquer chamada remota ou validacao local.
        Optional<RegistroIdempotencia> registroExistente =
                registroIdempotencia.buscar(comando.originadorId(), comando.idempotencyKey());
        if (registroExistente.isPresent()) {
            return responderPorRegistroExistente(registroExistente.get(), fingerprint, agora);
        }

        // Passo 6: validacoes puramente locais -- zero chamadas remotas se qualquer uma falhar.
        validarLocalmente(comando);
        ManifestacaoCliente manifestacao = construirManifestacao(comando);

        // Passo 7: autorizacao de recurso em CarteiraClientes. Sem direito, nenhuma consulta ao
        // CoreLegado acontece (AC23) -- a proxima linha so e alcancada se esta nao lancar.
        direitoDeAtendimento.confirmarDireitoDeAtendimento(comando.clienteId(), comando.contaId());

        // Passo 8: consulta ao CoreLegado pela ACL propria de Credito.
        DadosCreditoCore dados = dadosCreditoCore.consultar(comando.contaId())
                .orElseThrow(() -> new ContaNaoEncontradaException(
                        "O CoreLegado nao reconhece a conta " + comando.contaId().valor()));

        // Passo 9: optimistic check -- PRECEDE o passo 10 deliberadamente (spec: visto 5.000, Core
        // 6.000, pedido 5.500 -> 409, nunca 422).
        long limiteVigenteAtualCentavos = dados.limiteChequeEspecialVigente().centavos();
        if (comando.limiteVigenteVisto() != limiteVigenteAtualCentavos) {
            throw new LimiteVigenteDesatualizadoException(
                    "limiteVigenteVisto " + comando.limiteVigenteVisto()
                            + " diverge do vigente atual " + limiteVigenteAtualCentavos);
        }

        // Passo 10: so agora a comparacao contra o vigente autoritativo.
        if (comando.limiteSolicitado() <= limiteVigenteAtualCentavos) {
            throw new LimiteSolicitadoNaoAumentaException(
                    "limiteSolicitado " + comando.limiteSolicitado()
                            + " nao e estritamente maior que o vigente " + limiteVigenteAtualCentavos);
        }

        LimiteSolicitado limiteSolicitado = new LimiteSolicitado(comando.limiteSolicitado());

        // Passo 12: a versaoVigente e lida UMA UNICA VEZ aqui, no instante da captura do contexto
        // (guardrail do MotorDecisaoCredito) -- nunca mais tarde, na decisao.
        VersaoPoliticaCredito versaoVigente = motorDecisaoCredito.versaoVigente();
        ContextoDecisaoCredito contexto =
                ContextoDecisaoCredito.congelar(dados, limiteSolicitado, versaoVigente, agora);

        NovaSolicitacaoAumentoLimite novaSolicitacao = new NovaSolicitacaoAumentoLimite(
                comando.clienteId(),
                comando.contaId(),
                comando.originadorId(),
                OrigemSolicitacao.CLIENTE,
                manifestacao,
                contexto,
                new CorrelationId(UUID.randomUUID()),
                comando.idempotencyKey(),
                fingerprint,
                agora);

        // Passo 8 da spec (unicidade) + Fase 1 (TX1): delegado inteiramente ao resultado selado.
        ResultadoRegistroSolicitacao resultadoRegistro = solicitacoes.registrar(novaSolicitacao);

        return switch (resultadoRegistro) {
            case SolicitacaoCriada criada -> {
                ResultadoSubmissao decidido = decidirSolicitacaoAumentoLimite.executar(criada.id(), agora);
                yield comCriacaoNova(decidido, true);
            }
            case RegistroIdempotenteEncontrado encontrado ->
                    responderPorRegistroExistente(encontrado.registro(), fingerprint, agora);
            case SolicitacaoNaoTerminalExistente ignored -> throw new SolicitacaoNaoTerminalExistenteException(
                    "Ja existe solicitacao nao terminal para a conta " + comando.contaId().valor());
        };
    }

    /**
     * Ponto UNICO de reacao a um registro de idempotencia ja existente, reaproveitado tanto pelo
     * pre-check (passo 5) quanto pelo caminho de conflito de TX1 -- os dois caminhos classificam
     * com a MESMA funcao pura e respondem de forma identica (guardrail de concorrencia da porta).
     */
    private ResultadoSubmissao responderPorRegistroExistente(
            RegistroIdempotencia registro, String fingerprintCalculado, Instant agora) {
        var classificacao = ClassificadorIdempotencia.classificar(registro, fingerprintCalculado);
        if (classificacao == ClassificadorIdempotencia.Classificacao.FINGERPRINT_DIVERGENTE) {
            throw new IdempotenciaFingerprintDivergenteException(
                    "Idempotency-Key ja utilizada com um payload diferente");
        }

        // Fingerprint coincide: repeticao legitima. Decidir novamente (via DecidirSolicitacaoAumentoLimite)
        // e seguro tanto se a solicitacao ja foi decidida (replay puro, nenhuma escrita nova) quanto
        // se ainda esta SOLICITADA (retomada das Fases 2-3) -- a porta de aplicarDecisao e quem
        // decide atomicamente qual dos dois casos e este.
        ResultadoSubmissao decidido = decidirSolicitacaoAumentoLimite.executar(registro.solicitacaoId(), agora);
        return comCriacaoNova(decidido, false);
    }

    private static ResultadoSubmissao comCriacaoNova(ResultadoSubmissao resultado, boolean criacaoNova) {
        return new ResultadoSubmissao(
                resultado.solicitacaoId(), resultado.contaId(), resultado.status(),
                resultado.limiteChequeEspecialVigente(), resultado.limiteSolicitado(),
                resultado.decisao(), resultado.registradaEm(), criacaoNova, resultado.decidiuAgora());
    }

    private static void validarLocalmente(ComandoSolicitacaoAumentoLimite comando) {
        if (comando.limiteSolicitado() == null || comando.limiteSolicitado() <= 0) {
            throw new ComandoInvalidoException("limiteSolicitado deve ser positivo");
        }
        if (comando.limiteVigenteVisto() == null || comando.limiteVigenteVisto() < 0) {
            throw new ComandoInvalidoException("limiteVigenteVisto nao pode ser negativo");
        }
    }

    private static ManifestacaoCliente construirManifestacao(ComandoSolicitacaoAumentoLimite comando) {
        CanalManifestacao canal;
        try {
            canal = CanalManifestacao.valueOf(comando.canalManifestacao());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ComandoInvalidoException("canalManifestacao invalido: " + comando.canalManifestacao());
        }
        try {
            return new ManifestacaoCliente(canal, comando.observacao());
        } catch (IllegalArgumentException e) {
            throw new ComandoInvalidoException(e.getMessage());
        }
    }
}
