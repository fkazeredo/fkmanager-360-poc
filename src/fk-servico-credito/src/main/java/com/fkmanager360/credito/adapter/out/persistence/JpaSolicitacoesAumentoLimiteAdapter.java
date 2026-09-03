package com.fkmanager360.credito.adapter.out.persistence;

import com.fkmanager360.credito.adapter.out.persistence.repository.SolicitacaoAumentoLimiteRepository;
import com.fkmanager360.credito.application.port.out.CargaParaDecisao;
import com.fkmanager360.credito.application.port.out.EntradaHistorico;
import com.fkmanager360.credito.application.port.out.IdempotenciaEmProcessamentoException;
import com.fkmanager360.credito.application.port.out.IntencaoEfetivacao;
import com.fkmanager360.credito.application.port.out.NovaSolicitacaoAumentoLimite;
import com.fkmanager360.credito.application.port.out.RegistroIdempotenciaPort;
import com.fkmanager360.credito.application.port.out.RegistroIdempotenteEncontrado;
import com.fkmanager360.credito.application.port.out.ResultadoAplicacaoDecisao;
import com.fkmanager360.credito.application.port.out.ResultadoRegistroSolicitacao;
import com.fkmanager360.credito.application.port.out.SolicitacaoCriada;
import com.fkmanager360.credito.application.port.out.SolicitacaoNaoEncontradaException;
import com.fkmanager360.credito.application.port.out.SolicitacaoNaoTerminalExistente;
import com.fkmanager360.credito.application.port.out.SolicitacoesAumentoLimitePort;
import com.fkmanager360.credito.domain.AtorId;
import com.fkmanager360.credito.domain.DecisaoCredito;
import com.fkmanager360.credito.domain.IdempotencyKey;
import com.fkmanager360.credito.domain.SolicitacaoId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.util.UUID;

/**
 * Adapter de saida sobre o armazenamento privado de Credito (ADR-0014). Puramente mecanico (plano
 * #0003, D6): recebe uma {@link DecisaoCredito} ja calculada em {@link #aplicarDecisao}, nao
 * conhece {@code MotorDecisaoCredito} nem {@code PoliticaCredito} -- {@code ArchitectureTest}
 * garante isso estruturalmente.
 *
 * <p>A partir deste refactor, TX1 e TX2 vivem em {@link CreditoPersistenceOperations} -- um bean
 * SEPARADO, chamado daqui de fora, para que {@code @Transactional} atravesse o proxy AOP do Spring
 * (autoinvocacao dentro da mesma classe nao passaria por ele; era essa a razao de
 * {@code TransactionTemplate} programatico no adapter JDBC anterior). Este adapter cuida so da
 * traducao entre a port e o fragmento: gera o {@link SolicitacaoId}, decide o que fazer com as
 * excecoes que escapam da transacao, e delega leituras convencionais ao Spring Data.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class JpaSolicitacoesAumentoLimiteAdapter implements SolicitacoesAumentoLimitePort {

    private final SolicitacaoAumentoLimiteRepository solicitacaoRepository;
    private final CreditoPersistenceOperations operacoes;
    private final RegistroIdempotenciaPort registroIdempotencia;

    /**
     * TX1. Guardrail de concorrencia -- ver Javadoc de {@link SolicitacoesAumentoLimitePort}: as 4
     * escritas de {@link CreditoPersistenceOperations#registrarTx1} rodam numa unica transacao;
     * QUALQUER {@link DataIntegrityViolationException} levada por qualquer uma delas (na pratica,
     * so a primeira, em {@code uk_solicitacao_nao_terminal_por_conta}, ou a terceira, em
     * {@code pk_registro_idempotencia}, podem colidir sob concorrencia real) faz TX1 sofrer
     * rollback, e a classificacao da resposta NUNCA depende de qual constraint foi atingida --
     * apenas da releitura de {@code registro_idempotencia} feita DEPOIS do rollback, numa consulta
     * autocommit separada (via {@link #registroIdempotencia}, a mesma porta usada no pre-check da
     * Fase 0).
     */
    @Override
    public ResultadoRegistroSolicitacao registrar(NovaSolicitacaoAumentoLimite dados) {
        UUID novaId = UUID.randomUUID();
        try {
            operacoes.registrarTx1(dados, novaId);
            return new SolicitacaoCriada(new SolicitacaoId(novaId));
        } catch (DataIntegrityViolationException conflito) {
            log.info("Conflito ao registrar SolicitacaoAumentoLimite {}; reclassificando pela "
                            + "releitura do registro de idempotencia", novaId, conflito);
            return reclassificarAposConflitoDeTx1(dados.originadorId(), dados.idempotencyKey());
        }
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
        return solicitacaoRepository.findComContextoById(id.valor())
                .orElseThrow(() -> new SolicitacaoNaoEncontradaException(
                        "SolicitacaoAumentoLimite nao encontrada: " + id.valor()))
                .toCargaParaDecisao();
    }

    /**
     * TX2. O {@code SELECT status ... FOR UPDATE NOWAIT} sobre uma solicitacao ja bloqueada por
     * outra transacao concorrente devolve SQLState {@code 55P03} ({@code lock_not_available}).
     * <b>Confirmado empiricamente em S3</b> (teste
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
            return operacoes.aplicarDecisaoTx2(id, decisao, intencaoOuNull, entrada);
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
}
