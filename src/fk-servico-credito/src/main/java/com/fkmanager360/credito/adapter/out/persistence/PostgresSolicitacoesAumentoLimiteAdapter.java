package com.fkmanager360.credito.adapter.out.persistence;

import com.fkmanager360.credito.application.port.out.CargaParaDecisao;
import com.fkmanager360.credito.application.port.out.EntradaHistorico;
import com.fkmanager360.credito.application.port.out.IdempotenciaEmProcessamentoException;
import com.fkmanager360.credito.application.port.out.IntencaoEfetivacao;
import com.fkmanager360.credito.application.port.out.NovaSolicitacaoAumentoLimite;
import com.fkmanager360.credito.application.port.out.RegistroIdempotencia;
import com.fkmanager360.credito.application.port.out.RegistroIdempotenciaPort;
import com.fkmanager360.credito.application.port.out.RegistroIdempotenteEncontrado;
import com.fkmanager360.credito.application.port.out.ResultadoAplicacaoDecisao;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroSolicitacao;
import com.fkmanager360.credito.application.port.out.SolicitacaoCriada;
import com.fkmanager360.credito.application.port.out.SolicitacaoNaoEncontradaException;
import com.fkmanager360.credito.application.port.out.SolicitacaoNaoTerminalExistente;
import com.fkmanager360.credito.application.port.out.SolicitacoesAumentoLimitePort;
import com.fkmanager360.credito.domain.AtorHumano;
import com.fkmanager360.credito.domain.AtorId;
import com.fkmanager360.credito.domain.AtorOperacao;
import com.fkmanager360.credito.domain.AtorSistema;
import com.fkmanager360.credito.domain.ClassificacaoRiscoCreditoBase;
import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.ContextoDecisaoCredito;
import com.fkmanager360.credito.domain.CorrelationId;
import com.fkmanager360.credito.domain.DadosCreditoCore;
import com.fkmanager360.credito.domain.DecisaoCredito;
import com.fkmanager360.credito.domain.IdempotencyKey;
import com.fkmanager360.credito.domain.IncrementoSolicitado;
import com.fkmanager360.credito.domain.LimiteChequeEspecialVigente;
import com.fkmanager360.credito.domain.LimiteSolicitado;
import com.fkmanager360.credito.domain.MotivoDecisaoCredito;
import com.fkmanager360.credito.domain.ResultadoDecisaoCredito;
import com.fkmanager360.credito.domain.SituacaoConta;
import com.fkmanager360.credito.domain.SolicitacaoId;
import com.fkmanager360.credito.domain.StatusSolicitacaoAumentoLimite;
import com.fkmanager360.credito.domain.VersaoPoliticaCredito;
import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter de saida sobre o armazenamento privado de Credito (ADR-0014). Puramente mecanico (plano
 * #0003, D6): recebe uma {@link DecisaoCredito} ja calculada em {@link #aplicarDecisao}, nao
 * conhece {@code MotorDecisaoCredito} nem {@code PoliticaCredito} -- {@code ArchitectureTest}
 * garante isso estruturalmente.
 *
 * <p><b>Por que {@link TransactionTemplate} programatico, e nao {@code @Transactional}
 * declarativo:</b> {@link #registrar} precisa, no caminho de conflito, fazer TX1 sofrer rollback e
 * SO DEPOIS abrir uma segunda leitura autocommit numa conexao nova -- e
 * {@link #aplicarDecisao} precisa capturar a excecao de lock indisponivel de dentro da propria
 * transacao que tentou o {@code FOR UPDATE NOWAIT}. As duas coisas exigem controle explicito de
 * onde uma transacao termina e a proxima comeca dentro do MESMO metodo Java; um {@code this.outroMetodo()}
 * anotado com {@code @Transactional(propagation = REQUIRES_NEW)} chamado por autoinvocacao dentro
 * da mesma classe NAO passaria pelo proxy AOP do Spring (limitacao conhecida de
 * {@code @Transactional}) e silenciosamente reaproveitaria a transacao errada.
 * {@link TransactionTemplate}, construido sobre o mesmo {@link PlatformTransactionManager} que o
 * Spring Boot ja autoconfigura para {@code spring.datasource.*}, e o jeito programatico e robusto
 * de expressar exatamente essa demarcacao -- e continua sendo, para efeito da regra ArchUnit
 * {@code transacao_somente_na_persistencia}, uso de {@code org.springframework.transaction..}
 * dentro de {@code adapter.out.persistence}, exatamente como {@code @Transactional} seria.
 */
@Repository
public class PostgresSolicitacoesAumentoLimiteAdapter implements SolicitacoesAumentoLimitePort {

    private static final Logger log = LoggerFactory.getLogger(PostgresSolicitacoesAumentoLimiteAdapter.class);

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final RegistroIdempotenciaPort registroIdempotencia;

    public PostgresSolicitacoesAumentoLimiteAdapter(
            JdbcClient jdbcClient,
            PlatformTransactionManager transactionManager,
            RegistroIdempotenciaPort registroIdempotencia) {
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.registroIdempotencia = registroIdempotencia;
    }

    /**
     * TX1 (plano #0003, Fase 1). Guardrail de concorrencia -- ver Javadoc de
     * {@link SolicitacoesAumentoLimitePort}: os 4 INSERTs rodam numa unica transacao programatica;
     * QUALQUER {@link DataIntegrityViolationException} levada por qualquer um deles (na pratica, so
     * o INSERT #1 em {@code uk_solicitacao_nao_terminal_por_conta} ou o INSERT #3 em
     * {@code pk_registro_idempotencia} podem colidir sob concorrencia real) faz TX1 sofrer
     * rollback, e a classificacao da resposta NUNCA depende de qual constraint foi atingida --
     * apenas da releitura de {@code registro_idempotencia} feita DEPOIS do rollback, numa consulta
     * autocommit separada (via {@link #registroIdempotencia}, a mesma porta usada no pre-check da
     * Fase 0). A identificacao da constraint (melhor esforco, via a causa raiz
     * {@link PSQLException}) e usada apenas no log abaixo, nunca na decisao.
     */
    @Override
    public ResultadoRegistroSolicitacao registrar(NovaSolicitacaoAumentoLimite dados) {
        SolicitacaoId novaId = new SolicitacaoId(UUID.randomUUID());
        try {
            transactionTemplate.executeWithoutResult(status -> inserirTx1(novaId, dados));
            return new SolicitacaoCriada(novaId);
        } catch (DataIntegrityViolationException conflito) {
            log.info("Conflito ao registrar SolicitacaoAumentoLimite (constraint={}); "
                            + "reclassificando pela releitura do registro de idempotencia",
                    nomeDaConstraintViolada(conflito).orElse("desconhecida"), conflito);
            return reclassificarAposConflitoDeTx1(dados.originadorId(), dados.idempotencyKey());
        }
    }

    private void inserirTx1(SolicitacaoId novaId, NovaSolicitacaoAumentoLimite dados) {
        // INSERT #1 -- so este pode colidir em uk_solicitacao_nao_terminal_por_conta.
        jdbcClient.sql("""
                        insert into solicitacao_aumento_limite
                            (id, cliente_id, conta_id, originador_id, origem_solicitacao, canal_manifestacao,
                             observacao, status, correlation_id, efetivacao_id, registrada_em, atualizada_em)
                        values
                            (:id, :clienteId, :contaId, :originadorId, :origemSolicitacao, :canalManifestacao,
                             :observacao, 'SOLICITADA', :correlationId, null, :registradaEm, :registradaEm)
                        """)
                .param("id", novaId.valor())
                .param("clienteId", dados.clienteId().valor())
                .param("contaId", dados.contaId().valor())
                .param("originadorId", dados.originadorId().valor())
                .param("origemSolicitacao", dados.origemSolicitacao().name())
                .param("canalManifestacao", dados.manifestacaoCliente().canalManifestacao().name())
                .param("observacao", dados.manifestacaoCliente().observacao())
                .param("correlationId", dados.correlationId().valor())
                .param("registradaEm", Timestamp.from(dados.registradaEm()))
                .update();

        // INSERT #2 -- FK para uma solicitacao_id recem-gerada por UUID aleatorio: nunca colide.
        DadosCreditoCore dadosCore = dados.contextoDecisaoCredito().dadosCreditoCore();
        jdbcClient.sql("""
                        insert into contexto_decisao_credito
                            (solicitacao_id, limite_cheque_especial_vigente, situacao_conta,
                             classificacao_risco_credito_base, limite_solicitado, incremento_solicitado,
                             versao_politica_credito, capturado_em, dados_credito_core_fonte,
                             dados_credito_core_consultado_em)
                        values
                            (:solicitacaoId, :limiteVigente, :situacaoConta, :classificacaoRisco,
                             :limiteSolicitado, :incrementoSolicitado, :versaoPolitica, :capturadoEm,
                             :fonte, :consultadoEm)
                        """)
                .param("solicitacaoId", novaId.valor())
                .param("limiteVigente", dadosCore.limiteChequeEspecialVigente().centavos())
                .param("situacaoConta", dadosCore.situacaoConta().name())
                .param("classificacaoRisco", dadosCore.classificacaoRiscoCreditoBase().name())
                .param("limiteSolicitado", dados.contextoDecisaoCredito().limiteSolicitado().centavos())
                .param("incrementoSolicitado", dados.contextoDecisaoCredito().incrementoSolicitado().centavos())
                .param("versaoPolitica", dados.contextoDecisaoCredito().versaoPoliticaCredito().valor())
                .param("capturadoEm", Timestamp.from(dados.contextoDecisaoCredito().capturadoEm()))
                .param("fonte", dadosCore.fonte())
                .param("consultadoEm", Timestamp.from(dadosCore.consultadoEm()))
                .update();

        // INSERT #3 -- so este pode colidir em pk_registro_idempotencia (mesma key, conta diferente
        // da que colidiria no INSERT #1).
        jdbcClient.sql("""
                        insert into registro_idempotencia (originador_id, idempotency_key, fingerprint, solicitacao_id, criado_em)
                        values (:originadorId, :idempotencyKey, :fingerprint, :solicitacaoId, :criadoEm)
                        """)
                .param("originadorId", dados.originadorId().valor())
                .param("idempotencyKey", dados.idempotencyKey().valor())
                .param("fingerprint", dados.fingerprint())
                .param("solicitacaoId", novaId.valor())
                .param("criadoEm", Timestamp.from(dados.registradaEm()))
                .update();

        // INSERT #4 -- fato_id deterministico a partir de uma solicitacao_id recem-gerada: nunca colide.
        AtorColunas autor = colunasDoAtor(new AtorHumano(dados.originadorId()));
        jdbcClient.sql("""
                        insert into historico_solicitacao (solicitacao_id, fato_id, tipo_fato, ator_tipo, ator_id, ocorrido_em)
                        values (:solicitacaoId, :fatoId, 'SOLICITACAO_REGISTRADA', :atorTipo, :atorId, :ocorridoEm)
                        """)
                .param("solicitacaoId", novaId.valor())
                .param("fatoId", "SOLICITACAO:" + novaId.valor())
                .param("atorTipo", autor.tipo())
                .param("atorId", autor.id())
                .param("ocorridoEm", Timestamp.from(dados.registradaEm()))
                .update();
    }

    private ResultadoRegistroSolicitacao reclassificarAposConflitoDeTx1(AtorId originadorId, IdempotencyKey key) {
        return registroIdempotencia.buscar(originadorId, key)
                .<ResultadoRegistroSolicitacao>map(RegistroIdempotenteEncontrado::new)
                .orElseGet(SolicitacaoNaoTerminalExistente::new);
    }

    /**
     * Leitura simples, sem transacao propria: {@code contexto_decisao_credito} e imutavel (nenhum
     * UPDATE em codigo algum), entao le-lo antes de TX2 nao corre risco de os fatos mudarem
     * (plano #0003, Fase 2).
     */
    @Override
    public CargaParaDecisao carregarParaDecisao(SolicitacaoId id) {
        return jdbcClient.sql("""
                        select s.status, s.conta_id, s.correlation_id,
                               c.limite_cheque_especial_vigente, c.situacao_conta, c.classificacao_risco_credito_base,
                               c.limite_solicitado, c.incremento_solicitado, c.versao_politica_credito, c.capturado_em,
                               c.dados_credito_core_fonte, c.dados_credito_core_consultado_em
                        from solicitacao_aumento_limite s
                        join contexto_decisao_credito c on c.solicitacao_id = s.id
                        where s.id = :id
                        """)
                .param("id", id.valor())
                .query((rs, rowNum) -> new CargaParaDecisao(
                        StatusSolicitacaoAumentoLimite.valueOf(rs.getString("status")),
                        new ContextoDecisaoCredito(
                                new DadosCreditoCore(
                                        new LimiteChequeEspecialVigente(rs.getLong("limite_cheque_especial_vigente")),
                                        SituacaoConta.valueOf(rs.getString("situacao_conta")),
                                        ClassificacaoRiscoCreditoBase.valueOf(rs.getString("classificacao_risco_credito_base")),
                                        rs.getTimestamp("dados_credito_core_consultado_em").toInstant(),
                                        rs.getString("dados_credito_core_fonte")),
                                new LimiteSolicitado(rs.getLong("limite_solicitado")),
                                new IncrementoSolicitado(rs.getLong("incremento_solicitado")),
                                new VersaoPoliticaCredito(rs.getString("versao_politica_credito")),
                                rs.getTimestamp("capturado_em").toInstant()),
                        new ContaId(rs.getString("conta_id")),
                        new CorrelationId(rs.getObject("correlation_id", UUID.class))))
                .optional()
                .orElseThrow(() -> new SolicitacaoNaoEncontradaException(
                        "SolicitacaoAumentoLimite nao encontrada: " + id.valor()));
    }

    /**
     * TX2 (plano #0003, Fase 3). O {@code SELECT ... FOR UPDATE NOWAIT} sobre uma solicitacao ja
     * bloqueada por outra transacao concorrente devolve SQLState {@code 55P03}
     * ({@code lock_not_available}). <b>Confirmado empiricamente em S3</b> (teste
     * {@code aplicarDecisao_forUpdateNowaitSobConcorrenciaReal_...}, com duas conexoes reais): ao
     * contrario do que a documentacao do plano deste ticket presumia, o
     * {@code SQLErrorCodeSQLExceptionTranslator} do Spring (nesta versao de Spring
     * Framework/driver PostgreSQL) NAO tem esse codigo mapeado para
     * {@link org.springframework.dao.CannotAcquireLockException} -- ele cai no fallback generico
     * {@link UncategorizedSQLException}, envolvendo o {@link SQLException} original. Este metodo
     * trata os dois casos: {@link PessimisticLockingFailureException} (e suas subclasses, incluindo
     * {@code CannotAcquireLockException}) por defensividade, caso uma versao futura do Spring passe
     * a mapear {@code 55P03} explicitamente; e, no caminho realmente observado hoje,
     * {@link UncategorizedSQLException} com {@code SQLState = 55P03} verificado explicitamente --
     * qualquer outro {@code UncategorizedSQLException} e relancado sem traducao, para nao mascarar
     * um erro de banco diferente como se fosse contencao de lock.
     */
    @Override
    public ResultadoAplicacaoDecisao aplicarDecisao(
            SolicitacaoId id, DecisaoCredito decisao, IntencaoEfetivacao intencaoOuNull, EntradaHistorico entrada) {
        try {
            return transactionTemplate.execute(status -> aplicarDecisaoTx2(id, decisao, intencaoOuNull, entrada));
        } catch (PessimisticLockingFailureException lockIndisponivel) {
            throw new IdempotenciaEmProcessamentoException(
                    "SolicitacaoAumentoLimite " + id.valor() + " esta sendo decidida em outra requisicao concorrente");
        } catch (UncategorizedSQLException possivelLockIndisponivel) {
            if (!isLockNotAvailable(possivelLockIndisponivel)) {
                throw possivelLockIndisponivel;
            }
            throw new IdempotenciaEmProcessamentoException(
                    "SolicitacaoAumentoLimite " + id.valor() + " esta sendo decidida em outra requisicao concorrente");
        }
    }

    private static boolean isLockNotAvailable(UncategorizedSQLException e) {
        SQLException raiz = e.getSQLException();
        return raiz != null && "55P03".equals(raiz.getSQLState());
    }

    private ResultadoAplicacaoDecisao aplicarDecisaoTx2(
            SolicitacaoId id, DecisaoCredito decisao, IntencaoEfetivacao intencaoOuNull, EntradaHistorico entrada) {

        StatusSolicitacaoAumentoLimite statusAtual = lockarStatusAtual(id);

        if (statusAtual != StatusSolicitacaoAumentoLimite.SOLICITADA) {
            // Ja decidida (replay) ou em outro estado: nada e reescrito -- devolve exatamente o
            // que ja esta persistido (plano #0003, Fase 3, passo 2).
            DecisaoCredito decisaoPersistida = carregarDecisaoPersistida(id);
            return new ResultadoAplicacaoDecisao(false, statusAtual, decisaoPersistida);
        }

        AtorColunas autorDecisao = colunasDoAtor(decisao.autor());
        jdbcClient.sql("""
                        insert into decisao_credito
                            (solicitacao_id, resultado, motivo, versao_politica_credito, decidida_em, autor_tipo, autor_id)
                        values (:id, :resultado, :motivo, :versaoPolitica, :decididaEm, :autorTipo, :autorId)
                        """)
                .param("id", id.valor())
                .param("resultado", decisao.resultado().name())
                .param("motivo", decisao.motivo().name())
                .param("versaoPolitica", decisao.versaoPoliticaCredito().valor())
                .param("decididaEm", Timestamp.from(decisao.decididaEm()))
                .param("autorTipo", autorDecisao.tipo())
                .param("autorId", autorDecisao.id())
                .update();

        boolean aprovada = decisao.resultado() == ResultadoDecisaoCredito.APROVADA;
        StatusSolicitacaoAumentoLimite statusResultante = aprovada
                ? StatusSolicitacaoAumentoLimite.AGUARDANDO_EFETIVACAO
                : StatusSolicitacaoAumentoLimite.REJEITADA;
        // Estruturalmente impossivel gravar efetivacao_id numa rejeicao: so existe UUID aqui
        // quando intencaoOuNull != null, que so acontece no ramo aprovado (DecidirSolicitacaoAumentoLimite).
        UUID efetivacaoIdOuNull = aprovada ? intencaoOuNull.efetivacaoId().valor() : null;

        jdbcClient.sql("""
                        update solicitacao_aumento_limite
                        set status = :status, efetivacao_id = CAST(:efetivacaoId AS uuid), atualizada_em = :atualizadaEm
                        where id = :id
                        """)
                .param("status", statusResultante.name())
                .param("efetivacaoId", efetivacaoIdOuNull)
                .param("atualizadaEm", Timestamp.from(decisao.decididaEm()))
                .param("id", id.valor())
                .update();

        if (aprovada) {
            // Rejeicao NUNCA passa por aqui: nenhuma linha de Outbox nasce fora deste ramo.
            jdbcClient.sql("""
                            insert into outbox_mensagem
                                (message_id, tipo, destino, solicitacao_id, efetivacao_id, conta_id,
                                 limite_cheque_especial_vigente_esperado, limite_solicitado, correlation_id, criado_em)
                            values
                                (:messageId, 'EfetivarLimite', 'CORE_LEGADO', :solicitacaoId, :efetivacaoId, :contaId,
                                 :limiteVigenteEsperado, :limiteSolicitado, :correlationId, :criadoEm)
                            """)
                    .param("messageId", intencaoOuNull.messageId())
                    .param("solicitacaoId", id.valor())
                    .param("efetivacaoId", intencaoOuNull.efetivacaoId().valor())
                    .param("contaId", intencaoOuNull.contaId().valor())
                    .param("limiteVigenteEsperado", intencaoOuNull.limiteChequeEspecialVigenteEsperado().centavos())
                    .param("limiteSolicitado", intencaoOuNull.limiteSolicitado().centavos())
                    .param("correlationId", intencaoOuNull.correlationId().valor())
                    .param("criadoEm", Timestamp.from(decisao.decididaEm()))
                    .update();
        }

        AtorColunas autorHistorico = colunasDoAtor(entrada.autor());
        jdbcClient.sql("""
                        insert into historico_solicitacao (solicitacao_id, fato_id, tipo_fato, ator_tipo, ator_id, ocorrido_em)
                        values (:solicitacaoId, :fatoId, :tipoFato, :atorTipo, :atorId, :ocorridoEm)
                        """)
                .param("solicitacaoId", id.valor())
                .param("fatoId", entrada.fatoId())
                .param("tipoFato", entrada.tipoFato().name())
                .param("atorTipo", autorHistorico.tipo())
                .param("atorId", autorHistorico.id())
                .param("ocorridoEm", Timestamp.from(entrada.ocorridoEm()))
                .update();

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

    private DecisaoCredito carregarDecisaoPersistida(SolicitacaoId id) {
        return jdbcClient.sql("""
                        select resultado, motivo, versao_politica_credito, decidida_em, autor_tipo, autor_id
                        from decisao_credito where solicitacao_id = :id
                        """)
                .param("id", id.valor())
                .query((rs, rowNum) -> new DecisaoCredito(
                        ResultadoDecisaoCredito.valueOf(rs.getString("resultado")),
                        MotivoDecisaoCredito.valueOf(rs.getString("motivo")),
                        new VersaoPoliticaCredito(rs.getString("versao_politica_credito")),
                        rs.getTimestamp("decidida_em").toInstant(),
                        reconstruirAtor(rs.getString("autor_tipo"), rs.getString("autor_id"))))
                .single();
    }

    /**
     * Melhor esforco, so para log/diagnostico (nunca para decidir a resposta -- ver Javadoc de
     * {@link #registrar}): caminha a cadeia de causas ate achar a {@link PSQLException} raiz e
     * devolve o nome da constraint que o servidor reportou, quando disponivel.
     */
    private static Optional<String> nomeDaConstraintViolada(DataIntegrityViolationException e) {
        Throwable causa = e.getCause();
        while (causa != null) {
            if (causa instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
                return Optional.ofNullable(psql.getServerErrorMessage().getConstraint());
            }
            causa = causa.getCause();
        }
        return Optional.empty();
    }

    private static AtorOperacao reconstruirAtor(String tipo, String id) {
        return switch (tipo) {
            case "HUMANO" -> new AtorHumano(new AtorId(id));
            case "SISTEMA" -> new AtorSistema(id);
            default -> throw new IllegalStateException("ator_tipo desconhecido em decisao_credito: " + tipo);
        };
    }

    private static AtorColunas colunasDoAtor(AtorOperacao ator) {
        return switch (ator) {
            case AtorHumano h -> new AtorColunas("HUMANO", h.id().valor());
            case AtorSistema s -> new AtorColunas("SISTEMA", s.nome());
        };
    }

    private record AtorColunas(String tipo, String id) {
    }
}
