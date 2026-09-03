package com.fkmanager360.credito.adapter.out.persistence;

import com.fkmanager360.credito.adapter.out.persistence.entity.DecisaoCreditoEntity;
import com.fkmanager360.credito.adapter.out.persistence.entity.HistoricoSolicitacaoEntity;
import com.fkmanager360.credito.adapter.out.persistence.entity.SolicitacaoAumentoLimiteEntity;
import com.fkmanager360.credito.adapter.out.persistence.repository.DecisaoCreditoRepository;
import com.fkmanager360.credito.adapter.out.persistence.repository.HistoricoSolicitacaoRepository;
import com.fkmanager360.credito.adapter.out.persistence.repository.SolicitacaoAumentoLimiteRepository;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.ResultadoEfetivacaoRecebido;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroEfetivacao;
import com.fkmanager360.credito.application.port.out.SolicitacaoNaoEncontradaException;
import com.fkmanager360.credito.domain.AtorOperacao;
import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.SolicitacaoAumentoLimite;
import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Unica implementacao da regra de conclusao da efetivacao (ADR-0009): {@link #registrar}
 * correlaciona por {@link EfetivacaoId} -- nunca por {@code ProtocoloCore} -- sob
 * {@code PESSIMISTIC_WRITE} (mesma disciplina de TX2, #0003: checar o estado atual sob lock antes
 * de decidir se ha algo a escrever), e usa o MESMO {@link SolicitacaoAumentoLimite} de dominio ja
 * exaustivamente testado em S1 desde #0003 -- nenhuma segunda tabela de transicoes.
 *
 * <p>Chamada tanto de dentro da transacao ja aberta por
 * {@code JpaEntregasEfetivacaoAdapter#concluirComFalhaDefinitiva} (#0004, via propagacao
 * {@code REQUIRED} padrao) quanto, no futuro, standalone pelo callback (#0005) e pela
 * reconciliacao (#0006) -- nenhuma delas duplica esta porta.
 */
@Repository
@RequiredArgsConstructor
public class JpaResultadoEfetivacaoAdapter implements ResultadoEfetivacaoPort {

    private final SolicitacaoAumentoLimiteRepository solicitacaoRepository;
    private final HistoricoSolicitacaoRepository historicoRepository;
    private final DecisaoCreditoRepository decisaoRepository;

    @Override
    @Transactional
    public ResultadoRegistroEfetivacao registrar(
            EfetivacaoId efetivacaoId, ResultadoEfetivacaoRecebido resultado, AtorOperacao autor, Instant agora) {

        SolicitacaoAumentoLimiteEntity entity = solicitacaoRepository.buscarPorEfetivacaoIdParaAtualizar(efetivacaoId.valor())
                .orElseThrow(() -> new SolicitacaoNaoEncontradaException(
                        "Nenhuma SolicitacaoAumentoLimite para EfetivacaoId " + efetivacaoId.valor()));

        StatusSolicitacaoAumentoLimite statusAtual = StatusSolicitacaoAumentoLimite.valueOf(entity.getStatus());
        if (statusAtual.isTerminal()) {
            // Ja concluida (callback + dispatcher convergindo, redelivery, etc.): nada e
            // reescrito -- esta e a garantia de idempotencia do caso de uso unico de conclusao.
            return new ResultadoRegistroEfetivacao(false, statusAtual, null);
        }

        StatusSolicitacaoAumentoLimite statusResultante = switch (resultado) {
            case ResultadoEfetivacaoRecebido.FalhaDefinitiva falhaDefinitiva -> {
                SolicitacaoAumentoLimite transicionada = new SolicitacaoAumentoLimite(statusAtual)
                        .transicionarPara(StatusSolicitacaoAumentoLimite.FALHA_EFETIVACAO);
                solicitacaoRepository.atualizarStatusEMotivoFalha(
                        entity.getId(), transicionada.status().name(), falhaDefinitiva.motivo().name(), agora);
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

        return new ResultadoRegistroEfetivacao(true, statusResultante, permanencia);
    }
}
