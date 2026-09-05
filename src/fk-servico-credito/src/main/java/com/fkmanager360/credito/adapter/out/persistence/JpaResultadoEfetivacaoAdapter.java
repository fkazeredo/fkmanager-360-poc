package com.fkmanager360.credito.adapter.out.persistence;

import com.fkmanager360.credito.adapter.out.persistence.entity.DecisaoCreditoEntity;
import com.fkmanager360.credito.adapter.out.persistence.entity.HistoricoSolicitacaoEntity;
import com.fkmanager360.credito.adapter.out.persistence.entity.SolicitacaoAumentoLimiteEntity;
import com.fkmanager360.credito.adapter.out.persistence.repository.DecisaoCreditoRepository;
import com.fkmanager360.credito.adapter.out.persistence.repository.HistoricoSolicitacaoRepository;
import com.fkmanager360.credito.adapter.out.persistence.repository.SolicitacaoAumentoLimiteRepository;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoRecebido;
import com.fkmanager360.credito.application.port.out.ResultadoIndeterminacao;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroEfetivacao;
import com.fkmanager360.credito.application.port.out.SolicitacaoNaoEncontradaException;
import com.fkmanager360.credito.domain.AtorOperacao;
import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.ProtocoloCore;
import com.fkmanager360.credito.domain.SolicitacaoAumentoLimite;
import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Unica implementacao da regra de conclusao da efetivacao (ADR-0009): {@link #registrar}
 * correlaciona por {@link EfetivacaoId} -- nunca por {@code ProtocoloCore} -- sob
 * {@code PESSIMISTIC_WRITE} (mesma disciplina de TX2, #0003: checar o estado atual sob lock antes
 * de decidir se ha algo a escrever), e usa o MESMO {@link SolicitacaoAumentoLimite} de dominio ja
 * exaustivamente testado em S1 desde #0003 -- nenhuma segunda tabela de transicoes.
 *
 * <p><b>Classificacao terminal em tres eixos (#0005).</b> Quando a solicitacao ja esta terminal,
 * {@link #classificarTerminal} decide "identico" vs "contraditorio" comparando, sempre os tres:
 * (1) {@code ProtocoloCore} persistido vs informado agora; (2) status/motivo persistido vs
 * resultado recebido; (3) (quando sucesso) {@code limiteEfetivado} vs {@code LimiteSolicitado}
 * congelado. Protocolo divergente e SEMPRE contradicao, mesmo com os outros dois eixos coerentes
 * -- nunca tratado como duplicado (regra normativa do Owner, #0005).
 *
 * <p>Chamada tanto de dentro da unidade transacional aberta pela {@code TransacaoPort} na
 * composicao fenced de {@code RegistrarResultadoEfetivacao#executarSobClaim} (#0004, via
 * propagacao {@code REQUIRED} padrao) quanto standalone pelo callback (#0005, via {@code executar})
 * e, no futuro, pela reconciliacao (#0006) -- nenhuma delas duplica esta porta.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class JpaResultadoEfetivacaoAdapter implements ResultadoEfetivacaoPort {

    private final SolicitacaoAumentoLimiteRepository solicitacaoRepository;
    private final HistoricoSolicitacaoRepository historicoRepository;
    private final DecisaoCreditoRepository decisaoRepository;

    @Override
    @Transactional
    public ResultadoRegistroEfetivacao registrar(
            EfetivacaoId efetivacaoId, ResultadoEfetivacaoRecebido resultado,
            Optional<ProtocoloCore> protocoloInformado, AtorOperacao autor, Instant agora) {

        SolicitacaoAumentoLimiteEntity entity = solicitacaoRepository.buscarPorEfetivacaoIdParaAtualizar(efetivacaoId.valor())
                .orElseThrow(() -> new SolicitacaoNaoEncontradaException(
                        "Nenhuma SolicitacaoAumentoLimite para EfetivacaoId " + efetivacaoId.valor()));

        StatusSolicitacaoAumentoLimite statusAtual = StatusSolicitacaoAumentoLimite.valueOf(entity.getStatus());
        Optional<String> protocoloExistente = Optional.ofNullable(entity.getProtocoloCore());

        if (statusAtual.isTerminal()) {
            return classificarTerminal(entity, statusAtual, protocoloExistente, protocoloInformado, resultado);
        }

        if (!protocoloCoerente(protocoloExistente, protocoloInformado)) {
            // "Preservar o estado conhecido" (spec, bullet "protocolo contraditorio"): o
            // ProtocoloCore ja persistido nunca e sobrescrito por um valor diferente.
            log.warn("ProtocoloCore divergente para efetivacaoId={}: existente={}, informadoAgora={} "
                            + "-- nada sobrescrito, estado conhecido preservado",
                    efetivacaoId.valor(), protocoloExistente.orElse(null),
                    protocoloInformado.map(ProtocoloCore::valor).orElse(null));
            return new ResultadoRegistroEfetivacao.ProtocoloDivergente();
        }

        if (resultado instanceof ResultadoEfetivacaoRecebido.Sucesso sucesso
                && !limiteEfetivadoCoerente(entity.getId(), sucesso.limiteEfetivadoCentavos())) {
            log.warn("Callback de sucesso incoerente para efetivacaoId={}: limiteEfetivado={} nao "
                            + "coincide com o LimiteSolicitado congelado -- nao transiciona, operacao "
                            + "permanece recuperavel",
                    efetivacaoId.valor(), sucesso.limiteEfetivadoCentavos());
            return new ResultadoRegistroEfetivacao.SucessoIncoerente();
        }

        if (protocoloInformado.isPresent() && protocoloExistente.isEmpty()) {
            // Mesmo efeito de JdbcEntregasEfetivacaoAdapter#registrarAceite: aprende o protocolo e
            // registra o fato "ACEITE:" so na PRIMEIRA vez que algum caminho o aprende -- se o
            // dispatcher ja o tiver feito antes, protocoloExistente aqui ja esta preenchido e este
            // bloco nao roda (nenhuma segunda entrada de historico, AC14).
            solicitacaoRepository.atualizarProtocoloCore(entity.getId(), protocoloInformado.get().valor(), agora);
            historicoRepository.saveAndFlush(
                    HistoricoSolicitacaoEntity.instrucaoAceitaPeloCore(entity.getId(), agora));
        }

        StatusSolicitacaoAumentoLimite statusResultante = switch (resultado) {
            case ResultadoEfetivacaoRecebido.FalhaDefinitiva falhaDefinitiva -> {
                SolicitacaoAumentoLimite transicionada = new SolicitacaoAumentoLimite(statusAtual)
                        .transicionarPara(StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO);
                solicitacaoRepository.atualizarStatusEMotivoFalha(
                        entity.getId(), transicionada.status().name(), falhaDefinitiva.motivo().name(), agora);
                yield transicionada.status();
            }
            case ResultadoEfetivacaoRecebido.Sucesso ignored -> {
                SolicitacaoAumentoLimite transicionada = new SolicitacaoAumentoLimite(statusAtual)
                        .transicionarPara(StatusSolicitacaoAumentoLimite.EFETIVADA);
                solicitacaoRepository.atualizarStatus(entity.getId(), transicionada.status().name(), agora);
                yield transicionada.status();
            }
        };

        historicoRepository.saveAndFlush(
                HistoricoSolicitacaoEntity.resultadoEfetivacaoRegistrado(entity.getId(), autor, agora));

        // AC36: permanencia entre a decisao aprovada (TX2, #0003 -- o instante em que a
        // solicitacao ENTROU em AGUARDANDO_EFETIVACAO) e esta conclusao. Sem identificador de
        // negocio associado -- so o valor do meter.
        Instant decididaEm = decisaoRepository.findById(entity.getId())
                .map(DecisaoCreditoEntity::getDecididaEm)
                .orElseThrow(() -> new IllegalStateException(
                        "DecisaoCredito ausente para SolicitacaoAumentoLimite " + entity.getId()));
        Duration permanencia = Duration.between(decididaEm, agora);

        return new ResultadoRegistroEfetivacao.Concluida(statusResultante, permanencia);
    }

    private ResultadoRegistroEfetivacao classificarTerminal(
            SolicitacaoAumentoLimiteEntity entity, StatusSolicitacaoAumentoLimite statusPersistido,
            Optional<String> protocoloExistente, Optional<ProtocoloCore> protocoloInformado,
            ResultadoEfetivacaoRecebido resultado) {

        boolean coerente = protocoloCoerente(protocoloExistente, protocoloInformado)
                && resultadoCoerenteComTerminal(entity, statusPersistido, resultado);

        return coerente
                ? new ResultadoRegistroEfetivacao.JaTerminalIdentica(statusPersistido)
                : new ResultadoRegistroEfetivacao.JaTerminalContraditoria(statusPersistido);
    }

    /**
     * #0006, AC16: correlaciona por {@code EfetivacaoId} sob o MESMO lock pessimista de
     * {@link #registrar} -- serializa contra qualquer callback/reconciliacao concorrente para a
     * MESMA solicitacao, exatamente pela mesma razao. So transiciona quando o status persistido
     * ainda e {@code AGUARDANDO_EFETIVACAO}; {@code EFETIVACAO_INDETERMINADA} ou terminal e
     * no-op idempotente -- a chamada NUNCA lanca nem reescreve.
     */
    @Override
    @Transactional
    public ResultadoIndeterminacao registrarIndeterminacao(EfetivacaoId efetivacaoId, Instant agora) {
        SolicitacaoAumentoLimiteEntity entity = solicitacaoRepository.buscarPorEfetivacaoIdParaAtualizar(efetivacaoId.valor())
                .orElseThrow(() -> new SolicitacaoNaoEncontradaException(
                        "Nenhuma SolicitacaoAumentoLimite para EfetivacaoId " + efetivacaoId.valor()));

        StatusSolicitacaoAumentoLimite statusAtual = StatusSolicitacaoAumentoLimite.valueOf(entity.getStatus());

        if (statusAtual.isTerminal()) {
            return new ResultadoIndeterminacao.JaTerminal(statusAtual);
        }
        if (statusAtual == StatusSolicitacaoAumentoLimite.EFETIVACAO_INDETERMINADA) {
            return new ResultadoIndeterminacao.JaEstavaIndeterminada();
        }

        SolicitacaoAumentoLimite transicionada = new SolicitacaoAumentoLimite(statusAtual)
                .transicionarPara(StatusSolicitacaoAumentoLimite.EFETIVACAO_INDETERMINADA);
        solicitacaoRepository.atualizarStatus(entity.getId(), transicionada.status().name(), agora);
        historicoRepository.saveAndFlush(
                HistoricoSolicitacaoEntity.efetivacaoIndeterminadaRegistrada(entity.getId(), agora));

        return new ResultadoIndeterminacao.IndeterminadaAgora();
    }

    private boolean resultadoCoerenteComTerminal(
            SolicitacaoAumentoLimiteEntity entity, StatusSolicitacaoAumentoLimite statusPersistido,
            ResultadoEfetivacaoRecebido resultado) {
        return switch (resultado) {
            case ResultadoEfetivacaoRecebido.FalhaDefinitiva falha ->
                    statusPersistido == StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO
                            && falha.motivo().name().equals(entity.getMotivoFalhaEfetivacao());
            case ResultadoEfetivacaoRecebido.Sucesso sucesso ->
                    statusPersistido == StatusSolicitacaoAumentoLimite.EFETIVADA
                            && limiteEfetivadoCoerente(entity.getId(), sucesso.limiteEfetivadoCentavos());
        };
    }

    private boolean limiteEfetivadoCoerente(UUID solicitacaoId, long limiteEfetivadoCentavos) {
        return limiteEfetivadoCentavos == solicitacaoRepository.buscarLimiteSolicitadoCongelado(solicitacaoId);
    }

    private static boolean protocoloCoerente(Optional<String> existente, Optional<ProtocoloCore> informado) {
        // Ausencia de qualquer um dos lados nao e divergencia: so ha o que comparar quando os
        // DOIS estao presentes.
        if (existente.isEmpty() || informado.isEmpty()) {
            return true;
        }
        return existente.get().equals(informado.get().valor());
    }
}
