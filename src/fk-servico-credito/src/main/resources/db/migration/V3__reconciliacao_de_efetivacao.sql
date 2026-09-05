-- #0006: reconciliacao de efetivacoes paradas alem do prazo (ADR-0009, emenda). Fronteira estrita
-- com o dispatcher de #0004: o dispatcher ENTREGA (outbox_entrega), o reconciliador PERGUNTA -- por
-- isso esta tabela e nova e separada, nunca reusa outbox_entrega, e o reconciliador nunca a toca
-- (e essa separacao, mais a ordem global de locks abaixo, que preserva a ausencia de deadlock com o
-- callback puro e com o dispatcher).
--
-- Bookkeeping MUTAVEL da agenda de reconciliacao, 1:1 com a solicitacao aprovada (mesma forma de
-- outbox_entrega ser 1:1 com outbox_mensagem em V2): claim/fencing identico ao dispatcher
-- (claim_id + claim_expira_em), proxima_consulta_em como agenda de curto prazo (backoff) e
-- janela_expira_em como o limite da janela normal de recuperacao automatica (spec, secao
-- "Reconciliacao") -- esgotada sem resultado autoritativo, a solicitacao entra em
-- EFETIVACAO_INDETERMINADA e proxima_consulta_em passa a usar o backoff-longo (polling de
-- recuperacao de baixa frequencia, deliberado -- nao um erro de configuracao).
CREATE TABLE reconciliacao_efetivacao (
    efetivacao_id        UUID        NOT NULL,
    status_reconciliacao VARCHAR(20) NOT NULL,
    tentativas           INT         NOT NULL DEFAULT 0,
    proxima_consulta_em  TIMESTAMPTZ NOT NULL,
    janela_expira_em     TIMESTAMPTZ NOT NULL,
    indeterminada_em     TIMESTAMPTZ,
    claim_id             UUID,
    claim_expira_em      TIMESTAMPTZ,
    atualizado_em        TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_reconciliacao_efetivacao PRIMARY KEY (efetivacao_id),
    CONSTRAINT fk_reconciliacao_efetivacao_solicitacao FOREIGN KEY (efetivacao_id)
        REFERENCES solicitacao_aumento_limite (efetivacao_id),
    CONSTRAINT ck_reconciliacao_efetivacao_status CHECK (status_reconciliacao IN ('PENDENTE', 'CONCLUIDA')),
    CONSTRAINT ck_reconciliacao_efetivacao_tentativas CHECK (tentativas >= 0)
);

-- Sem CHECK relacionando proxima_consulta_em e janela_expira_em: na CRIACAO da linha (TX2 e este
-- backfill) proxima_consulta_em < janela_expira_em vale por construcao (elegivel-apos < janela,
-- provado em S3), mas o backoff curto de um ciclo tardio dentro da janela pode legitimamente
-- empurrar proxima_consulta_em para depois de janela_expira_em sem que isso seja indeterminacao --
-- e a APLICACAO, no proximo claim, quem decide o desfecho a partir de "agora >= janela_expira_em",
-- nunca uma constraint fisica sobre os dois campos.

-- Candidatos ao claim (TX-A), mesmo idioma de ix_outbox_entrega_pendentes (V2): o filtro por claim
-- livre/expirado acontece na consulta (SKIP LOCKED), este indice so precisa cobrir o predicado mais
-- seletivo sob operacao normal.
CREATE INDEX ix_reconciliacao_efetivacao_pendentes ON reconciliacao_efetivacao (proxima_consulta_em)
    WHERE status_reconciliacao = 'PENDENTE';

COMMENT ON TABLE reconciliacao_efetivacao IS
    'Agenda de reconciliacao de efetivacao (ADR-0009, emenda; #0006), 1:1 com solicitacao_aumento_limite.efetivacao_id. status_reconciliacao e plano de CONSULTA, nunca estado de negocio -- StatusSolicitacaoAumentoLimite continua a unica maquina de estados funcional. So vira CONCLUIDA quando RegistrarResultadoEfetivacao efetivamente terminaliza a solicitacao (Concluida/JaTerminalIdentica/JaTerminalContraditoria) -- SucessoIncoerente e ProtocoloDivergente nunca terminalizam esta linha.';
COMMENT ON COLUMN reconciliacao_efetivacao.proxima_consulta_em IS
    'Agenda de curto prazo (backoff) enquanto PENDENTE e nao indeterminada; passa a usar o backoff-longo apos indeterminada_em ser preenchido -- polling de recuperacao de baixa frequencia ate uma resposta autoritativa, deliberado (spec, secao "Reconciliacao").';
COMMENT ON COLUMN reconciliacao_efetivacao.janela_expira_em IS
    'Limite da janela NORMAL de recuperacao automatica (spec: ~10min de demonstracao). Esgotada sem resultado autoritativo, a solicitacao entra em EFETIVACAO_INDETERMINADA dentro da mesma TX-B que descobre o esgotamento -- nunca em commit separado.';
COMMENT ON COLUMN reconciliacao_efetivacao.indeterminada_em IS
    'Preenchido quando a janela esgota com a solicitacao ainda AGUARDANDO_EFETIVACAO (transicao para EFETIVACAO_INDETERMINADA). NULL enquanto a recuperacao automatica normal ainda esta em curso.';
COMMENT ON COLUMN reconciliacao_efetivacao.claim_id IS
    'Fencing token identico ao de outbox_entrega.claim_id (V2): novo UUID a cada reclamacao bem-sucedida. Toda escrita de TX-B verifica claim_id + status_reconciliacao = PENDENTE sob lock fresco antes de qualquer efeito.';

-- Backfill: solicitacoes hoje AGUARDANDO_EFETIVACAO precisam de uma linha de agenda -- mas o
-- relogio NAO reinicia no instante desta migration (decisao do Owner). A agenda e reconstruida a
-- partir do instante durável do NASCIMENTO da intencao de efetivacao em TX2
-- (outbox_mensagem.criado_em, ja persistido e estavel por toda a vida da operacao -- ADR-0009,
-- emenda), com os defaults canonicos da spec gravados literalmente aqui (sem infraestrutura de
-- configuracao do Flyway so para o backfill): elegivel apos 60s, janela de 10min. Uma solicitacao
-- historicamente antiga pode nascer imediatamente elegivel ou ja com janela vencida -- correto e
-- desejado, porque preserva a idade REAL da operacao em vez de fingir que ela acabou de comecar.
-- Em um ambiente que nasce neste commit a consulta abaixo nao encontra linhas (mesma nota de V2).
INSERT INTO reconciliacao_efetivacao (efetivacao_id, status_reconciliacao, tentativas, proxima_consulta_em, janela_expira_em, atualizado_em)
    SELECT s.efetivacao_id, 'PENDENTE', 0,
           om.criado_em + INTERVAL '60 seconds',
           om.criado_em + INTERVAL '10 minutes',
           om.criado_em
    FROM solicitacao_aumento_limite s
    JOIN outbox_mensagem om ON om.efetivacao_id = s.efetivacao_id
    WHERE s.status = 'AGUARDANDO_EFETIVACAO';

-- Fato novo no historico funcional que #0006 introduz (spec, secao "Historico funcional"): entrada
-- em EFETIVACAO_INDETERMINADA quando a janela normal se esgota sem resultado autoritativo.
ALTER TABLE historico_solicitacao DROP CONSTRAINT ck_historico_tipo;
ALTER TABLE historico_solicitacao ADD CONSTRAINT ck_historico_tipo CHECK (tipo_fato IN (
    'SOLICITACAO_REGISTRADA', 'DECISAO_AUTOMATICA_REGISTRADA', 'EFETIVACAO_SOLICITADA',
    'INSTRUCAO_ACEITA_PELO_CORE', 'RESULTADO_EFETIVACAO_REGISTRADO', 'EFETIVACAO_INDETERMINADA_REGISTRADA'));
