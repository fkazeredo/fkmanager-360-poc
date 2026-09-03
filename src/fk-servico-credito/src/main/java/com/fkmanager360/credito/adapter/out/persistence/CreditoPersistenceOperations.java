package com.fkmanager360.credito.adapter.out.persistence;

import com.fkmanager360.credito.adapter.out.persistence.entity.ContextoDecisaoCreditoEntity;
import com.fkmanager360.credito.adapter.out.persistence.entity.DecisaoCreditoEntity;
import com.fkmanager360.credito.adapter.out.persistence.entity.HistoricoSolicitacaoEntity;
import com.fkmanager360.credito.adapter.out.persistence.entity.OutboxMensagemEntity;
import com.fkmanager360.credito.adapter.out.persistence.entity.RegistroIdempotenciaEntity;
import com.fkmanager360.credito.adapter.out.persistence.entity.SolicitacaoAumentoLimiteEntity;
import com.fkmanager360.credito.adapter.out.persistence.repository.DecisaoCreditoRepository;
import com.fkmanager360.credito.adapter.out.persistence.repository.HistoricoSolicitacaoRepository;
import com.fkmanager360.credito.adapter.out.persistence.repository.OutboxMensagemRepository;
import com.fkmanager360.credito.adapter.out.persistence.repository.SolicitacaoAumentoLimiteRepository;
import com.fkmanager360.credito.application.port.out.EntradaHistorico;
import com.fkmanager360.credito.application.port.out.IntencaoEfetivacao;
import com.fkmanager360.credito.application.port.out.NovaSolicitacaoAumentoLimite;
import com.fkmanager360.credito.application.port.out.ResultadoAplicacaoDecisao;
import com.fkmanager360.credito.application.port.out.SolicitacaoNaoEncontradaException;
import com.fkmanager360.credito.domain.DecisaoCredito;
import com.fkmanager360.credito.domain.ResultadoDecisaoCredito;
import com.fkmanager360.credito.domain.SolicitacaoId;
import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * Fragmento transacional de TX1/TX2 (plano #0003, secoes 5 e 6), num bean SEPARADO dos adapters
 * que implementam as ports -- nao um metodo privado deles. A razao e a mesma que motivava
 * {@code TransactionTemplate} programatico no adapter JDBC anterior: {@code @Transactional}
 * declarativo so funciona atraves do proxy AOP do Spring, e uma chamada {@code this.outroMetodo()}
 * dentro da MESMA classe (autoinvocacao) nunca passa por ele. Ao mover TX1/TX2 para um bean
 * diferente, {@code JpaSolicitacoesAumentoLimiteAdapter} chama este bean de FORA -- uma chamada
 * cross-bean de verdade -- e o proxy aplica normalmente. Isso dispensa {@code TransactionTemplate}:
 * um metodo {@code @Transactional} simples basta.
 *
 * <p>Deliberadamente uma classe concreta, sem interface: nada mais implementa nem substitui este
 * fragmento (ADR-0020 -- abstracao sem consumidor e cerimonia, nao design).
 *
 * <p><b>Por que algumas escritas usam {@link EntityManager#persist} direto e outras usam
 * {@code repository.saveAndFlush(...)}:</b> a deteccao padrao do Spring Data de "entity nova" olha
 * se o campo {@code @Id} esta nulo. {@link SolicitacaoAumentoLimiteEntity} e
 * {@link RegistroIdempotenciaEntity} tem identificador ATRIBUIDO pela aplicacao ANTES do save (UUID
 * aleatorio / chave composta) -- {@code save()} chamaria {@code EntityManager.merge(...)}, que faz
 * um SELECT de existencia redundante antes do INSERT, exatamente no trecho mais sensivel do modulo
 * (a ordem fisica dos INSERTs de TX1). {@link ContextoDecisaoCreditoEntity} nao tem repository
 * proprio (nenhum caso de uso le esta tabela isoladamente -- ela so existe junto de
 * {@code SolicitacaoAumentoLimiteEntity}, via {@code @EntityGraph}). As tres usam
 * {@code entityManager.persist(...)} direto, inequivoco (sempre INSERT). Ja
 * {@link DecisaoCreditoEntity} (identificador {@code @MapsId}, nulo ate o persist) e
 * {@link HistoricoSolicitacaoEntity} (identificador {@code GENERATED ALWAYS AS IDENTITY}, tambem
 * nulo ate o persist) sao corretamente detectadas como novas pelo Spring Data -- usam
 * {@code repository.saveAndFlush(...)} sem ambiguidade. {@link OutboxMensagemEntity} tem
 * identificador atribuido como as duas primeiras, mas o SELECT redundante do {@code merge()} e
 * inofensivo aqui: ao contrario de TX1, este INSERT roda sob o lock exclusivo de TX2 (ver
 * {@link #aplicarDecisaoTx2}), entao nao ha concorrencia real disputando esta linha.
 *
 * <p><b>{@code @Repository}, nao {@code @Component}</b> -- verificado empiricamente: sem esse
 * estereotipo especifico, {@code PersistenceExceptionTranslationPostProcessor} (autoconfigurado
 * por {@code @EnableJpaRepositories}) nao aplica o advice de traducao de excecao a este bean, e o
 * {@code registrarTx1} deixaria escapar {@code org.hibernate.exception.ConstraintViolationException}
 * bruto em vez de {@link org.springframework.dao.DataIntegrityViolationException} -- quebrando o
 * {@code catch} de {@code JpaSolicitacoesAumentoLimiteAdapter#registrar}, que e o guardrail de
 * concorrencia inteiro. Ao contrario do {@code JdbcClient} (que sempre traduz, com ou sem
 * {@code @Repository}), o caminho JPA exige esse estereotipo explicitamente -- mesmo esta classe
 * nao sendo um "repositorio" no sentido DDD, e essa a razao funcional real da anotacao em Spring.
 */
@Repository
@RequiredArgsConstructor
public class CreditoPersistenceOperations {

    @PersistenceContext
    private EntityManager entityManager;

    private final JdbcClient jdbcClient;
    private final SolicitacaoAumentoLimiteRepository solicitacaoRepository;
    private final DecisaoCreditoRepository decisaoRepository;
    private final OutboxMensagemRepository outboxRepository;
    private final HistoricoSolicitacaoRepository historicoRepository;

    /**
     * TX1 (plano #0003, Fase 1). As quatro escritas rodam NA ORDEM -- {@code solicitacao} ->
     * {@code contexto} -> {@code registro_idempotencia} -> {@code historico} -- porque a ordem
     * fisica dos INSERTs e semantica de negocio, nao um detalhe de implementacao: o indice unico
     * {@code uk_solicitacao_nao_terminal_por_conta} (atingido pelo primeiro INSERT) precisa colidir
     * ANTES da PK de idempotencia (atingida pelo terceiro), para que
     * {@code JpaSolicitacoesAumentoLimiteAdapter} classifique corretamente qualquer conflito
     * relendo {@code registro_idempotencia} depois do rollback -- ver Javadoc de
     * {@link com.fkmanager360.credito.application.port.out.SolicitacoesAumentoLimitePort}. Hibernate
     * pode reordenar escritas no flush; {@code persist()}/{@code saveAndFlush()} explicito por
     * passo e o que garante que a ordem das chamadas Java seja a ordem do SQL.
     */
    @Transactional
    public void registrarTx1(NovaSolicitacaoAumentoLimite dados, UUID novaId) {
        SolicitacaoAumentoLimiteEntity solicitacao = SolicitacaoAumentoLimiteEntity.de(dados, novaId);
        entityManager.persist(solicitacao);
        entityManager.flush();

        ContextoDecisaoCreditoEntity contexto = ContextoDecisaoCreditoEntity.de(solicitacao, dados.contextoDecisaoCredito());
        entityManager.persist(contexto);
        entityManager.flush();

        RegistroIdempotenciaEntity registro = RegistroIdempotenciaEntity.de(dados, novaId);
        entityManager.persist(registro);
        entityManager.flush();

        HistoricoSolicitacaoEntity historico =
                HistoricoSolicitacaoEntity.solicitacaoRegistrada(novaId, dados.originadorId(), dados.registradaEm());
        historicoRepository.saveAndFlush(historico);
    }

    /**
     * TX2 (plano #0003, Fase 3). O {@code SELECT ... FOR UPDATE NOWAIT} permanece {@link JdbcClient}
     * nativo, DENTRO desta mesma transacao -- excecao explicitamente autorizada (ver Javadoc de
     * {@code JpaSolicitacoesAumentoLimiteAdapter#aplicarDecisao} para o comportamento de excecao
     * verificado empiricamente em S3). O lock e adquirido ANTES de qualquer escrita JPA nesta
     * transacao, entao nao ha hazard de flush entre as duas tecnologias -- ambas compartilham a
     * mesma conexao, ja que {@code JdbcClient} e o {@code EntityManager} resolvem para o mesmo
     * {@code DataSource} sob o mesmo {@code JpaTransactionManager}.
     */
    @Transactional
    public ResultadoAplicacaoDecisao aplicarDecisaoTx2(
            SolicitacaoId id, DecisaoCredito decisao, IntencaoEfetivacao intencaoOuNull, EntradaHistorico entrada) {

        StatusSolicitacaoAumentoLimite statusAtual = lockarStatusAtual(id);

        if (statusAtual != StatusSolicitacaoAumentoLimite.SOLICITADA) {
            // Ja decidida (replay) ou em outro estado: nada e reescrito -- devolve exatamente o
            // que ja esta persistido (plano #0003, Fase 3, passo 2).
            DecisaoCredito decisaoPersistida = decisaoRepository.findById(id.valor())
                    .orElseThrow(() -> new IllegalStateException(
                            "DecisaoCredito ausente para SolicitacaoAumentoLimite " + id.valor()
                                    + " apesar de status != SOLICITADA (" + statusAtual + ")"))
                    .toDomain();
            return new ResultadoAplicacaoDecisao(false, statusAtual, decisaoPersistida);
        }

        // Referencia sem SELECT: o lock acima ja confirma que a linha existe -- getReference()
        // basta para o Hibernate derivar o solicitacao_id via @MapsId sem reconsultar a linha.
        SolicitacaoAumentoLimiteEntity referencia = entityManager.getReference(SolicitacaoAumentoLimiteEntity.class, id.valor());

        DecisaoCreditoEntity decisaoEntity = DecisaoCreditoEntity.de(referencia, decisao);
        decisaoRepository.saveAndFlush(decisaoEntity);

        boolean aprovada = decisao.resultado() == ResultadoDecisaoCredito.APROVADA;
        StatusSolicitacaoAumentoLimite statusResultante = aprovada
                ? StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO
                : StatusSolicitacaoAumentoLimite.REJEITADA;
        // Estruturalmente impossivel gravar efetivacao_id numa rejeicao: so existe UUID aqui
        // quando intencaoOuNull != null, que so acontece no ramo aprovado (DecidirSolicitacaoAumentoLimite).
        UUID efetivacaoIdOuNull = aprovada ? intencaoOuNull.efetivacaoId().valor() : null;

        solicitacaoRepository.atualizarStatusEEfetivacao(id.valor(), statusResultante.name(), efetivacaoIdOuNull, decisao.decididaEm());

        if (aprovada) {
            // Rejeicao NUNCA passa por aqui: nenhuma linha de Outbox nasce fora deste ramo.
            OutboxMensagemEntity outbox = OutboxMensagemEntity.de(id.valor(), intencaoOuNull, decisao.decididaEm());
            outboxRepository.saveAndFlush(outbox);

            // #0004: a entrega nasce PENDENTE atomicamente com a intencao, no MESMO commit de TX2
            // (plano #0004, secao 3). outbox_entrega nao tem entity JPA propria -- ninguem mais
            // insere nesta tabela, so o dispatcher faz UPDATE sob lock (JpaEntregasEfetivacaoAdapter,
            // mesma justificativa de raw SQL do FOR UPDATE NOWAIT abaixo, ADR-0023).
            jdbcClient.sql("""
                    insert into outbox_entrega (message_id, status_entrega, tentativas, proxima_tentativa_em, atualizado_em)
                    values (:messageId, 'PENDENTE', 0, :agora, :agora)
                    """)
                    .param("messageId", intencaoOuNull.messageId())
                    .param("agora", Timestamp.from(decisao.decididaEm()))
                    .update();
        }

        HistoricoSolicitacaoEntity historico = HistoricoSolicitacaoEntity.de(id.valor(), entrada);
        historicoRepository.saveAndFlush(historico);

        return new ResultadoAplicacaoDecisao(true, statusResultante, decisao);
    }

    private StatusSolicitacaoAumentoLimite lockarStatusAtual(SolicitacaoId id) {
        try {
            String status = jdbcClient.sql("select status from solicitacao_aumento_limite where id = :id for update nowait")
                    .param("id", id.valor())
                    .query(String.class)
                    .single();
            return StatusSolicitacaoAumentoLimite.valueOf(status);
        } catch (EmptyResultDataAccessException semLinha) {
            throw new SolicitacaoNaoEncontradaException("SolicitacaoAumentoLimite nao encontrada: " + id.valor());
        }
    }
}
