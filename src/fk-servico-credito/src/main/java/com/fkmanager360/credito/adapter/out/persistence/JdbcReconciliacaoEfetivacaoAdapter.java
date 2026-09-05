package com.fkmanager360.credito.adapter.out.persistence;

import com.fkmanager360.credito.application.port.out.EfetivacaoReconciliacaoReclamada;
import com.fkmanager360.credito.application.port.out.ReclamacaoReconciliacao;
import com.fkmanager360.credito.application.port.out.ReconciliacaoEfetivacaoPort;
import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.ProtocoloCore;
import com.fkmanager360.credito.domain.SolicitacaoId;
import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Claim/fencing/agenda de {@code reconciliacao_efetivacao} (#0006), espelhando 1:1 o idioma de
 * {@link JdbcEntregasEfetivacaoAdapter} (#0004) -- mesma justificativa de raw SQL via
 * {@link JdbcClient} em vez de query derivation ou JPQL (ADR-0023: {@code SKIP LOCKED} nao tem
 * equivalente em nenhum dos dois, e o fencing exige controlar exatamente qual linha e bloqueada e
 * quando). Sem entity/repository Spring Data proprios pelo mesmo motivo: nenhum caso de uso deste
 * modulo LE esta tabela para apresentacao.
 *
 * <p><b>Claim unitario:</b> {@link #reclamarProxima} reclama NO MAXIMO um ciclo por chamada -- o
 * loop de "ate {@code lote} ciclos por tick" vive no adapter de agendamento.
 *
 * <p><b>Fencing:</b> toda escrita de resultado ({@link #terminalizar}, {@link #reagendar},
 * {@link #reagendarAposIndeterminacao}) e {@code MANDATORY} -- so podem correr dentro da mesma
 * {@code TransacaoPort} que ja verificou {@link #claimAindaValido} sob lock fresco.
 *
 * <p><b>Ordem global de locks preservada:</b> {@link #reclamarProxima} bloqueia so
 * {@code reconciliacao_efetivacao} ({@code for update of re}), nunca a linha de
 * {@code solicitacao_aumento_limite} lida junto para decidir o desfecho "ja terminal" -- e
 * {@link #claimAindaValido}, chamado ANTES de {@code RegistrarResultadoEfetivacao} tomar o lock de
 * {@code solicitacao_aumento_limite} dentro da mesma TX-B, quem garante que
 * {@code reconciliacao_efetivacao} e sempre bloqueada primeiro.
 */
@Repository
@RequiredArgsConstructor
public class JdbcReconciliacaoEfetivacaoAdapter implements ReconciliacaoEfetivacaoPort {

    private final JdbcClient jdbcClient;

    @Override
    @Transactional
    public ReclamacaoReconciliacao reclamarProxima(Instant agora, Duration lease) {
        Optional<CandidatoReconciliacao> candidatoEncontrado = jdbcClient.sql("""
                select re.efetivacao_id, re.tentativas, re.janela_expira_em, re.indeterminada_em,
                       s.id as solicitacao_id, s.status, s.protocolo_core
                from reconciliacao_efetivacao re
                join solicitacao_aumento_limite s on s.efetivacao_id = re.efetivacao_id
                where re.status_reconciliacao = 'PENDENTE'
                  and re.proxima_consulta_em <= :agora
                  and (re.claim_id is null or re.claim_expira_em <= :agora)
                order by re.proxima_consulta_em
                for update of re skip locked
                limit 1
                """)
                .param("agora", Timestamp.from(agora))
                .query((rs, rowNum) -> new CandidatoReconciliacao(
                        (UUID) rs.getObject("efetivacao_id"),
                        rs.getInt("tentativas"),
                        rs.getTimestamp("janela_expira_em").toInstant(),
                        rs.getTimestamp("indeterminada_em") == null ? null : rs.getTimestamp("indeterminada_em").toInstant(),
                        (UUID) rs.getObject("solicitacao_id"),
                        rs.getString("status"),
                        rs.getString("protocolo_core")))
                .optional();

        if (candidatoEncontrado.isEmpty()) {
            return new ReclamacaoReconciliacao.NenhumaPendente();
        }

        CandidatoReconciliacao candidato = candidatoEncontrado.get();
        StatusSolicitacaoAumentoLimite statusSolicitacao = StatusSolicitacaoAumentoLimite.valueOf(candidato.status());

        if (statusSolicitacao.isTerminal()) {
            // Concluida por outro caminho (tipicamente callback) antes deste claim: terminaliza a
            // linha de reconciliacao dentro do MESMO lock, sem devolver claim -- nenhuma consulta
            // ao Core para este ciclo (plano #0006 -- e o proprio "o reconciliador so pergunta").
            marcarConcluida(candidato.efetivacaoId(), agora);
            return new ReclamacaoReconciliacao.JaTerminalDescartada(statusSolicitacao);
        }

        UUID novoClaimId = UUID.randomUUID();
        int novaTentativa = candidato.tentativas() + 1;
        jdbcClient.sql("""
                update reconciliacao_efetivacao
                set tentativas = :tentativas, claim_id = :claimId, claim_expira_em = :claimExpiraEm, atualizado_em = :agora
                where efetivacao_id = :efetivacaoId
                """)
                .param("tentativas", novaTentativa)
                .param("claimId", novoClaimId)
                .param("claimExpiraEm", Timestamp.from(agora.plus(lease)))
                .param("agora", Timestamp.from(agora))
                .param("efetivacaoId", candidato.efetivacaoId())
                .update();

        EfetivacaoReconciliacaoReclamada claim = new EfetivacaoReconciliacaoReclamada(
                novoClaimId,
                new EfetivacaoId(candidato.efetivacaoId()),
                new SolicitacaoId(candidato.solicitacaoId()),
                Optional.ofNullable(candidato.protocoloCore()).map(ProtocoloCore::new),
                candidato.indeterminadaEm() != null,
                candidato.janelaExpiraEm(),
                novaTentativa);

        return new ReclamacaoReconciliacao.Reclamada(claim);
    }

    /**
     * {@code MANDATORY}: exige a transacao aberta pela {@code TransacaoPort} da composicao de
     * {@code ReconciliarEfetivacoes} -- fora dela o {@code FOR UPDATE} evaporaria no autocommit e o
     * fencing seria ilusorio. Bloqueante (nao {@code SKIP LOCKED}): sob disputa real pela MESMA
     * linha, a segunda transacao espera a primeira commitar e ve o estado ja atualizado.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claimAindaValido(EfetivacaoReconciliacaoReclamada claim) {
        Optional<FencingRow> linha = jdbcClient.sql(
                        "select status_reconciliacao, claim_id from reconciliacao_efetivacao where efetivacao_id = :efetivacaoId for update")
                .param("efetivacaoId", claim.efetivacaoId().valor())
                .query((rs, rowNum) -> new FencingRow(rs.getString("status_reconciliacao"), (UUID) rs.getObject("claim_id")))
                .optional();

        return linha.filter(l -> "PENDENTE".equals(l.statusReconciliacao()) && claim.claimId().equals(l.claimId()))
                .isPresent();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void terminalizar(EfetivacaoReconciliacaoReclamada claim, Instant agora) {
        marcarConcluida(claim.efetivacaoId().valor(), agora);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reagendar(EfetivacaoReconciliacaoReclamada claim, Instant proximaConsultaEm, Instant agora) {
        jdbcClient.sql("""
                update reconciliacao_efetivacao
                set proxima_consulta_em = :proximaConsultaEm, claim_id = null, claim_expira_em = null, atualizado_em = :agora
                where efetivacao_id = :efetivacaoId
                """)
                .param("proximaConsultaEm", Timestamp.from(proximaConsultaEm))
                .param("agora", Timestamp.from(agora))
                .param("efetivacaoId", claim.efetivacaoId().valor())
                .update();
    }

    /**
     * {@code indeterminada_em = COALESCE(indeterminada_em, :agora)}: grava so na primeira vez --
     * reentradas subsequentes (fase de polling de baixa frequencia) preservam o instante ORIGINAL
     * em que a indeterminacao comecou, nunca o reescrevem.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reagendarAposIndeterminacao(EfetivacaoReconciliacaoReclamada claim, Instant proximaConsultaEm, Instant agora) {
        jdbcClient.sql("""
                update reconciliacao_efetivacao
                set proxima_consulta_em = :proximaConsultaEm, indeterminada_em = coalesce(indeterminada_em, :agora),
                    claim_id = null, claim_expira_em = null, atualizado_em = :agora
                where efetivacao_id = :efetivacaoId
                """)
                .param("proximaConsultaEm", Timestamp.from(proximaConsultaEm))
                .param("agora", Timestamp.from(agora))
                .param("efetivacaoId", claim.efetivacaoId().valor())
                .update();
    }

    private void marcarConcluida(UUID efetivacaoId, Instant agora) {
        jdbcClient.sql("""
                update reconciliacao_efetivacao
                set status_reconciliacao = 'CONCLUIDA', claim_id = null, claim_expira_em = null, atualizado_em = :agora
                where efetivacao_id = :efetivacaoId
                """)
                .param("agora", Timestamp.from(agora))
                .param("efetivacaoId", efetivacaoId)
                .update();
    }

    private record CandidatoReconciliacao(
            UUID efetivacaoId, int tentativas, Instant janelaExpiraEm, Instant indeterminadaEm,
            UUID solicitacaoId, String status, String protocoloCore) {
    }

    private record FencingRow(String statusReconciliacao, UUID claimId) {
    }
}
