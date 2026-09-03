-- #0004: entrega da instrucao de efetivacao ao CoreLegado (ADR-0009, plano #0004). outbox_mensagem
-- permanece intencao IMUTAVEL -- nenhum caso de uso desta migration ou dos tickets que a consomem
-- faz UPDATE nela. Todo metadado MUTAVEL de entrega (tentativas, backoff, claim/fencing, desfecho)
-- vive em outbox_entrega, tabela nova, 1:1 com a intencao -- preserva a decisao ja registrada em
-- #0003 de separar intencao de metadado de entrega.
--
-- Fencing token (claim_id): decisao do Owner (revisao do plano #0004). O lease (claim_expira_em)
-- sozinho nao bastava -- um worker cujo lease expirou pode ainda estar em voo (HTTP em andamento)
-- quando outro worker reclama a mesma entrega; sem fencing, o resultado atrasado do primeiro
-- worker poderia sobrescrever o que o segundo ja persistiu. Toda escrita de resultado (TX-B)
-- verifica message_id + claim_id + status_entrega = 'PENDENTE' antes de aplicar qualquer efeito;
-- claim obsoleto e descartado integralmente, sem tocar esta tabela nem solicitacao_aumento_limite.
CREATE TABLE outbox_entrega (
    message_id              UUID        NOT NULL,
    status_entrega          VARCHAR(20) NOT NULL,
    tentativas              INT         NOT NULL DEFAULT 0,
    proxima_tentativa_em    TIMESTAMPTZ,
    claim_id                UUID,
    claim_expira_em         TIMESTAMPTZ,
    ultima_classe_resultado VARCHAR(20),
    ultimo_erro             VARCHAR(200),
    atualizado_em           TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_outbox_entrega PRIMARY KEY (message_id),
    CONSTRAINT fk_outbox_entrega_mensagem FOREIGN KEY (message_id)
        REFERENCES outbox_mensagem (message_id),
    CONSTRAINT ck_outbox_entrega_status CHECK (status_entrega IN
        ('PENDENTE', 'ACEITA', 'FALHA_DEFINITIVA', 'ESGOTADA', 'INDETERMINADA')),
    CONSTRAINT ck_outbox_entrega_classe CHECK (ultima_classe_resultado IS NULL OR ultima_classe_resultado IN
        ('ACEITE', 'TRANSITORIO', 'DEFINITIVO', 'INDETERMINADO')),
    CONSTRAINT ck_outbox_entrega_tentativas CHECK (tentativas >= 0)
);

-- Candidatos ao claim (TX-A): PENDENTE devido -- o filtro por claim livre/expirado e por
-- proxima_tentativa_em acontece na consulta (SKIP LOCKED), este indice so precisa cobrir o
-- primeiro predicado, o mais seletivo sob operacao normal (a maioria das linhas nao esta PENDENTE).
CREATE INDEX ix_outbox_entrega_pendentes ON outbox_entrega (proxima_tentativa_em)
    WHERE status_entrega = 'PENDENTE';

COMMENT ON TABLE outbox_entrega IS
    'Metadado MUTAVEL de entrega da intencao de EfetivacaoLimite (ADR-0009, plano #0004), 1:1 com outbox_mensagem (intencao imutavel). status_entrega e plano de ENTREGA, nunca estado de negocio -- StatusSolicitacaoAumentoLimite continua a unica maquina de estados funcional.';
COMMENT ON COLUMN outbox_entrega.tentativas IS
    'Episodios de entrega RESERVADOS (incrementado no claim/TX-A, antes do HTTP) -- NAO e contador auditavel de POSTs efetivamente recebidos pelo Core. Crash entre claim e HTTP pode deixar este valor maior que o numero real de requisicoes enviadas; isso e aceito porque limita episodios e ESGOTADA nunca e falha de negocio (decisao do Owner, plano #0004).';
COMMENT ON COLUMN outbox_entrega.claim_id IS
    'Fencing token: novo UUID a cada reclamacao bem-sucedida (reclamarProxima). Toda escrita de resultado (TX-B) so aplica efeito se claim_id ainda for o corrente E status_entrega = PENDENTE -- caso contrario o resultado pertence a um worker obsoleto e e descartado integralmente (decisao do Owner, plano #0004).';
COMMENT ON COLUMN outbox_entrega.claim_expira_em IS
    'Lease: prazo apos o qual outro worker pode reclamar esta entrega mesmo sem resultado do worker anterior. Nao substitui o fencing por claim_id -- o lease so decide QUANDO reclamar de novo e possivel; o claim_id decide QUAL resultado, atrasado ou nao, ainda vale.';
COMMENT ON COLUMN outbox_entrega.ultimo_erro IS
    'Descricao tecnica curta e sanitizada da ultima falha transitoria/indeterminada -- nunca payload ou COD-RET do Core (ADR-0005, ADR-0017): a ACL traduz antes de chegar aqui.';

-- Backfill: cobre o caso de um ambiente ja ter linhas de outbox_mensagem de #0003 sem entrega
-- associada (banco de demonstracao ja em uso). Em um ambiente que nasce neste commit a consulta
-- nao encontra linhas.
INSERT INTO outbox_entrega (message_id, status_entrega, tentativas, proxima_tentativa_em, atualizado_em)
    SELECT message_id, 'PENDENTE', 0, criado_em, criado_em FROM outbox_mensagem;

-- Estados locais que #0004 passa a preencher em solicitacao_aumento_limite (secao 2 do plano):
-- protocolo_core no aceite; motivo_falha_efetivacao quando o Core devolve resultado definitivo ja
-- na instrucao (AC15). Nenhum dos dois e coluna nova de workflow -- StatusSolicitacaoAumentoLimite
-- (ja existente) continua sendo a unica maquina de estados.
ALTER TABLE solicitacao_aumento_limite
    ADD COLUMN protocolo_core           VARCHAR(20),
    ADD COLUMN motivo_falha_efetivacao  VARCHAR(40);

-- ATENCAO (POC): o simulador-core-legado gera numPrt a partir de um contador em memoria que
-- reinicia do zero a cada restart do container (deliberado, ver EfetivacoesLegadoStore). Como
-- credito_db persiste alem de restarts do simulador, resetar SO o simulador (sem tambem resetar
-- credito_db) pode reemitir um numPrt ja usado por uma solicitacao anterior e violar esta
-- constraint dentro de um tick do dispatcher. Mitigacao operacional: sempre resetar os dois juntos
-- (ver memoria de sessao "Maven via Docker" / e2e); nenhum codigo de producao trata essa colisao.
ALTER TABLE solicitacao_aumento_limite
    ADD CONSTRAINT uk_solicitacao_protocolo_core UNIQUE (protocolo_core);

ALTER TABLE solicitacao_aumento_limite
    ADD CONSTRAINT ck_solicitacao_motivo_falha_efetivacao CHECK (motivo_falha_efetivacao IS NULL OR motivo_falha_efetivacao IN
        ('LIMITE_VIGENTE_DIVERGENTE', 'CONTA_INEXISTENTE', 'CONTA_BLOQUEADA_NA_EFETIVACAO', 'INSTRUCAO_INVALIDA'));

COMMENT ON COLUMN solicitacao_aumento_limite.protocolo_core IS
    'ProtocoloCore devolvido pelo CoreLegado no aceite da instrucao (CONTEXT.md de Credito). NULL ate o aceite; preenchido uma unica vez -- um valor divergente para o mesmo EfetivacaoId nunca sobrescreve o existente (anomalia observavel, nao erro de negocio).';
COMMENT ON COLUMN solicitacao_aumento_limite.motivo_falha_efetivacao IS
    'Preenchido somente quando status = FALHA_EFETIVACAO por retorno definitivo e autoritativo do Core na propria instrucao (AC15). NULL em qualquer outro status.';

-- Fatos de entrega que #0004 introduz no historico funcional (docs/contextos/credito/CONTEXT.md,
-- "Historico funcional"): EFETIVACAO_SOLICITADA na primeira tentativa de entrega,
-- INSTRUCAO_ACEITA_PELO_CORE no aceite, RESULTADO_EFETIVACAO_REGISTRADO na conclusao definitiva.
ALTER TABLE historico_solicitacao DROP CONSTRAINT ck_historico_tipo;
ALTER TABLE historico_solicitacao ADD CONSTRAINT ck_historico_tipo CHECK (tipo_fato IN (
    'SOLICITACAO_REGISTRADA', 'DECISAO_AUTOMATICA_REGISTRADA',
    'EFETIVACAO_SOLICITADA', 'INSTRUCAO_ACEITA_PELO_CORE', 'RESULTADO_EFETIVACAO_REGISTRADO'));
