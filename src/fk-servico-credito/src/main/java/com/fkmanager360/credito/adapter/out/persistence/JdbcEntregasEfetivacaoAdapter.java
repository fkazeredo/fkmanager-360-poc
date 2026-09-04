package com.fkmanager360.credito.adapter.out.persistence;

import com.fkmanager360.credito.adapter.out.persistence.entity.HistoricoSolicitacaoEntity;
import com.fkmanager360.credito.adapter.out.persistence.repository.HistoricoSolicitacaoRepository;
import com.fkmanager360.credito.adapter.out.persistence.repository.SolicitacaoAumentoLimiteRepository;
import com.fkmanager360.credito.application.port.out.EntregaEfetivacaoReclamada;
import com.fkmanager360.credito.application.port.out.EntregasEfetivacaoPort;
import com.fkmanager360.credito.application.port.out.IntencaoEfetivacao;
import com.fkmanager360.credito.application.port.out.ReclamacaoEntrega;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroEntrega;
import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.CorrelationId;
import com.fkmanager360.credito.domain.EfetivacaoId;
import com.fkmanager360.credito.domain.LimiteChequeEspecialVigente;
import com.fkmanager360.credito.domain.LimiteSolicitado;
import com.fkmanager360.credito.domain.ProtocoloCore;
import com.fkmanager360.credito.domain.SolicitacaoId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Claim/fencing/lifecycle de {@code outbox_entrega} (plano #0004, secoes 1 e 4 -- OD-1). Raw SQL
 * via {@link JdbcClient}, e nao Spring Data derivado, pela mesma classe de justificativa que
 * {@code CreditoPersistenceOperations#lockarStatusAtual} ja usa para o {@code FOR UPDATE NOWAIT} de
 * TX2 (ADR-0023): {@code SKIP LOCKED} nao tem equivalente em query derivation ou JPQL padrao, e o
 * fencing exige controlar exatamente qual linha e bloqueada e quando. {@code outbox_entrega} nao
 * tem entity/repository Spring Data proprios -- nenhum caso de uso deste modulo LE esta tabela
 * para apresentacao; toda a sua vida util e escrita condicionada por lock, o proprio criterio que
 * justifica a excecao.
 *
 * <p><b>Claim unitario (OD-1):</b> {@link #reclamarProxima} reclama NO MAXIMO uma entrega por
 * chamada. O loop de "ate {@code lote} episodios por tick" vive no adapter de agendamento.
 *
 * <p><b>Fencing (correcao do Owner sobre OD-1):</b> toda escrita de resultado
 * ({@link #registrarAceite}, {@link #reagendar}, {@link #marcarIndeterminada}) comeca por
 * {@link #fencingValido}, que adquire um lock FRESCO sobre a linha (TX-A ja liberou o seu antes do
 * HTTP) e so prossegue se o {@code claimId} apresentado ainda for o corrente e o
 * {@code status_entrega} ainda for {@code PENDENTE}. Claim obsoleto e descartado ANTES de qualquer
 * outra leitura ou escrita -- nem {@code outbox_entrega}, nem {@code solicitacao_aumento_limite},
 * nem historico, nem retorno que permita metrica de resultado.
 *
 * <p><b>Conclusao definitiva nao mora aqui</b> (revisao do Owner, 2026-09-04): quem a orquestra e
 * {@code RegistrarResultadoEfetivacao}, dentro de uma {@code TransacaoPort}; este adapter
 * contribui as operacoes {@code MANDATORY} {@link #claimAindaValido} e
 * {@link #terminalizarPorFalhaDefinitiva} para essa composicao, e nunca chama a aplicacao.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class JdbcEntregasEfetivacaoAdapter implements EntregasEfetivacaoPort {

    private final JdbcClient jdbcClient;
    private final SolicitacaoAumentoLimiteRepository solicitacaoRepository;
    private final HistoricoSolicitacaoRepository historicoRepository;

    @Override
    @Transactional
    public ReclamacaoEntrega reclamarProxima(Instant agora, int maxTentativas, Duration lease) {
        Optional<CandidatoEntrega> candidatoEncontrado = jdbcClient.sql("""
                select oe.message_id, oe.tentativas, om.solicitacao_id, om.efetivacao_id, om.conta_id,
                       om.limite_cheque_especial_vigente_esperado, om.limite_solicitado, om.correlation_id
                from outbox_entrega oe
                join outbox_mensagem om on om.message_id = oe.message_id
                where oe.status_entrega = 'PENDENTE'
                  and oe.proxima_tentativa_em <= :agora
                  and (oe.claim_id is null or oe.claim_expira_em <= :agora)
                order by oe.proxima_tentativa_em
                for update of oe skip locked
                limit 1
                """)
                .param("agora", Timestamp.from(agora))
                .query((rs, rowNum) -> new CandidatoEntrega(
                        (UUID) rs.getObject("message_id"),
                        rs.getInt("tentativas"),
                        (UUID) rs.getObject("solicitacao_id"),
                        (UUID) rs.getObject("efetivacao_id"),
                        rs.getString("conta_id"),
                        rs.getLong("limite_cheque_especial_vigente_esperado"),
                        rs.getLong("limite_solicitado"),
                        (UUID) rs.getObject("correlation_id")))
                .optional();

        if (candidatoEncontrado.isEmpty()) {
            return new ReclamacaoEntrega.NenhumaPendente();
        }

        CandidatoEntrega candidato = candidatoEncontrado.get();

        if (candidato.tentativas() >= maxTentativas) {
            // Crash entre o commit do claim e o envio HTTP da ultima tentativa reservada -- ou
            // esgotamento normal apos respostas transitorias -- sob o MESMO lock que reclamaria a
            // entrega (plano #0004, secao 1, regra normativa do Owner). Nenhum novo episodio HTTP.
            terminalizar(candidato.messageId(), StatusEntrega.ESGOTADA, null, null, agora);
            return new ReclamacaoEntrega.EsgotadaAgora();
        }

        UUID novoClaimId = UUID.randomUUID();
        int novaTentativa = candidato.tentativas() + 1;
        jdbcClient.sql("""
                update outbox_entrega
                set tentativas = :tentativas, claim_id = :claimId, claim_expira_em = :claimExpiraEm, atualizado_em = :agora
                where message_id = :messageId
                """)
                .param("tentativas", novaTentativa)
                .param("claimId", novoClaimId)
                .param("claimExpiraEm", Timestamp.from(agora.plus(lease)))
                .param("agora", Timestamp.from(agora))
                .param("messageId", candidato.messageId())
                .update();

        if (novaTentativa == 1) {
            // fatoId deterministico ("EFETIVACAO:"+solicitacaoId): so a primeira reclamacao chama
            // isto, entao reenvios nunca produzem uma segunda entrada (plano #0004, secao 2).
            historicoRepository.saveAndFlush(
                    HistoricoSolicitacaoEntity.efetivacaoSolicitada(candidato.solicitacaoId(), agora));
        }

        IntencaoEfetivacao intencao = new IntencaoEfetivacao(
                new EfetivacaoId(candidato.efetivacaoId()),
                candidato.messageId(),
                new ContaId(candidato.contaId()),
                new LimiteChequeEspecialVigente(candidato.limiteEsperado()),
                new LimiteSolicitado(candidato.limiteSolicitado()),
                new CorrelationId(candidato.correlationId()));

        return new ReclamacaoEntrega.Reclamada(new EntregaEfetivacaoReclamada(
                novoClaimId, intencao, new SolicitacaoId(candidato.solicitacaoId()), novaTentativa));
    }

    @Override
    @Transactional
    public ResultadoRegistroEntrega registrarAceite(EntregaEfetivacaoReclamada claim, ProtocoloCore protocoloCore, Instant agora) {
        if (!fencingValido(claim)) {
            return ResultadoRegistroEntrega.DESCARTADO_CLAIM_OBSOLETO;
        }

        // Le-depois-escreve sem lock proprio em protocolo_core: seguro APENAS porque
        // fencingValido() acima ja tomou um FOR UPDATE sobre a linha de outbox_entrega para este
        // message_id, que e 1:1 com esta solicitacao (uk_outbox_solicitacao) -- isso serializa
        // qualquer segundo worker que chegue aqui para a MESMA solicitacao. Um futuro chamador
        // deste repositorio que nao passe por fencingValido() primeiro reintroduz uma corrida real
        // sob READ COMMITTED.
        UUID solicitacaoId = claim.solicitacaoId().valor();
        Optional<String> existente = solicitacaoRepository.buscarProtocoloCore(solicitacaoId);

        ResultadoRegistroEntrega resultado;
        if (existente.isEmpty()) {
            solicitacaoRepository.atualizarProtocoloCore(solicitacaoId, protocoloCore.valor(), agora);
            historicoRepository.saveAndFlush(
                    HistoricoSolicitacaoEntity.instrucaoAceitaPeloCore(solicitacaoId, agora));
            resultado = ResultadoRegistroEntrega.APLICADO;
        } else if (existente.get().equals(protocoloCore.valor())) {
            // Reenvio recuperando o mesmo protocolo (AC11): idempotente, historico ja existe.
            resultado = ResultadoRegistroEntrega.APLICADO;
        } else {
            log.warn("ProtocoloCore divergente para solicitacaoId={}: existente={}, recebidoAgora={} "
                            + "-- existente preservado, nada sobrescrito",
                    solicitacaoId, existente.get(), protocoloCore.valor());
            resultado = ResultadoRegistroEntrega.APLICADO_COM_ANOMALIA_PROTOCOLO_DIVERGENTE;
        }

        terminalizar(claim.intencao().messageId(), StatusEntrega.ACEITA, ClasseResultado.ACEITE, null, agora);
        return resultado;
    }

    @Override
    @Transactional
    public ResultadoRegistroEntrega reagendar(
            EntregaEfetivacaoReclamada claim, Instant proximaTentativaEm, String erroSanitizado, Instant agora) {
        if (!fencingValido(claim)) {
            return ResultadoRegistroEntrega.DESCARTADO_CLAIM_OBSOLETO;
        }

        // Libera o claim IMEDIATAMENTE (lifecycle, plano #0004 secao 1): sem isto, o lease (~30s)
        // bloquearia o backoff curto (1s/2s/4s) ate expirar por conta propria.
        jdbcClient.sql("""
                update outbox_entrega
                set proxima_tentativa_em = :proximaTentativaEm, claim_id = null, claim_expira_em = null,
                    ultima_classe_resultado = :classeResultado, ultimo_erro = :erro, atualizado_em = :agora
                where message_id = :messageId
                """)
                .param("proximaTentativaEm", Timestamp.from(proximaTentativaEm))
                .param("classeResultado", ClasseResultado.TRANSITORIO.name())
                .param("erro", erroSanitizado, Types.VARCHAR)
                .param("agora", Timestamp.from(agora))
                .param("messageId", claim.intencao().messageId())
                .update();

        return ResultadoRegistroEntrega.APLICADO;
    }

    @Override
    @Transactional
    public ResultadoRegistroEntrega marcarIndeterminada(EntregaEfetivacaoReclamada claim, String erroSanitizado, Instant agora) {
        if (!fencingValido(claim)) {
            return ResultadoRegistroEntrega.DESCARTADO_CLAIM_OBSOLETO;
        }

        // OD-3: para sem concluir nada -- nenhuma escrita em solicitacao_aumento_limite nem
        // historico. Recuperacao pertence a #0006.
        terminalizar(claim.intencao().messageId(), StatusEntrega.INDETERMINADA, ClasseResultado.INDETERMINADO, erroSanitizado, agora);
        return ResultadoRegistroEntrega.APLICADO;
    }

    /**
     * {@code MANDATORY}: exige a transacao aberta pela {@code TransacaoPort} da composicao de
     * conclusao -- fora dela o {@code FOR UPDATE} evaporaria no autocommit e o fencing seria
     * ilusorio. Falha rapida em vez de mentir.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claimAindaValido(EntregaEfetivacaoReclamada claim) {
        return fencingValido(claim);
    }

    /** Mesma exigencia de transacao ativa de {@link #claimAindaValido} -- mesma composicao. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void terminalizarPorFalhaDefinitiva(EntregaEfetivacaoReclamada claim, Instant agora) {
        terminalizar(claim.intencao().messageId(), StatusEntrega.FALHA_DEFINITIVA, ClasseResultado.DEFINITIVO, null, agora);
    }

    /**
     * {@code SELECT ... FOR UPDATE} adquire um lock FRESCO sobre a linha -- TX-A ja liberou o seu
     * antes do HTTP -- e a verificacao acontece sob esse lock, antes de qualquer escrita.
     * Bloqueante (nao {@code SKIP LOCKED}): se duas transacoes concorrentes chegarem aqui para a
     * MESMA linha, a segunda espera a primeira commitar e entao ve o estado ja atualizado,
     * descartando corretamente -- e exatamente o cenario adversarial provado em S3.
     */
    private boolean fencingValido(EntregaEfetivacaoReclamada claim) {
        Optional<FencingRow> linha = jdbcClient.sql(
                        "select status_entrega, claim_id from outbox_entrega where message_id = :messageId for update")
                .param("messageId", claim.intencao().messageId())
                .query((rs, rowNum) -> new FencingRow(rs.getString("status_entrega"), (UUID) rs.getObject("claim_id")))
                .optional();

        return linha.filter(l -> StatusEntrega.PENDENTE.name().equals(l.statusEntrega()) && claim.claimId().equals(l.claimId()))
                .isPresent();
    }

    private void terminalizar(UUID messageId, StatusEntrega statusFinal, ClasseResultado classeResultadoOuNull,
                              String erroOuNull, Instant agora) {
        jdbcClient.sql("""
                update outbox_entrega
                set status_entrega = :statusFinal, claim_id = null, claim_expira_em = null, proxima_tentativa_em = null,
                    ultima_classe_resultado = :classeResultado, ultimo_erro = :erro, atualizado_em = :agora
                where message_id = :messageId
                """)
                .param("statusFinal", statusFinal.name())
                .param("classeResultado", classeResultadoOuNull == null ? null : classeResultadoOuNull.name(), Types.VARCHAR)
                .param("erro", erroOuNull, Types.VARCHAR)
                .param("agora", Timestamp.from(agora))
                .param("messageId", messageId)
                .update();
    }

    /**
     * Espelho 1:1 do CHECK {@code ck_outbox_entrega_status} de V2 -- o {@code name()} e o que vai
     * para a coluna. Privado ao adapter de proposito: estado de ENTREGA nunca e estado de negocio
     * (plano #0004, secao 2), entao o tipo nao pertence ao dominio nem a application.
     */
    private enum StatusEntrega {
        PENDENTE, ACEITA, FALHA_DEFINITIVA, ESGOTADA, INDETERMINADA
    }

    /** Espelho 1:1 dos valores de {@code ultima_classe_resultado} (V2). */
    private enum ClasseResultado {
        ACEITE, TRANSITORIO, DEFINITIVO, INDETERMINADO
    }

    private record CandidatoEntrega(
            UUID messageId, int tentativas, UUID solicitacaoId, UUID efetivacaoId, String contaId,
            long limiteEsperado, long limiteSolicitado, UUID correlationId) {
    }

    private record FencingRow(String statusEntrega, UUID claimId) {
    }
}
