-- credito_db nasce aqui (ADR-0010, ADR-0014): #0003 e o primeiro comportamento de Credito com
-- estado duravel -- a submissao da SolicitacaoAumentoLimite e a decisao automatica que a segue na
-- mesma requisicao (plano #0003, secao 2). Aplicada pelo Flyway embutido com a credencial
-- credito_migrator (DDL); a aplicacao (JdbcClient) roda com credito_app (DML) e nunca ve esta
-- migration nem a credencial que a executou.
--
-- Onde os CHECKs desta migration PARAM, deliberadamente: eles cobrem dominio de valor (os enums
-- do dominio Java, espelhados aqui como VARCHAR com CHECK IN (...)) e invariantes estruturais de
-- armazenamento -- dinheiro nunca negativo onde a regra exige, incremento coerente com as duas
-- parcelas que o definem, observacao nunca gravada em branco, e a restricao deste slice
-- (origem_solicitacao = 'CLIENTE'). Eles NAO cobrem a PoliticaCredito (as faixas de valor de
-- PoliticaCreditoV1 -- R$ 10.000,00 / R$ 2.000,00 -- vivem so em codigo Java) nem a maquina de
-- estados completa (a tabela de transicoes de StatusSolicitacaoAumentoLimite, incluindo quais
-- status permitem qual proximo status): duplicar qualquer uma das duas em SQL criaria uma segunda
-- fonte de verdade que evolui separada da primeira (S1 prova as duas exaustivamente em Java).
--
-- Dinheiro sempre BIGINT, em centavos. Nenhum NUMERIC, NENHUM ponto flutuante, em nenhuma coluna.
-- Toda constraint tem nome explicito -- usado em diagnostico e assertado em S3.

CREATE TABLE solicitacao_aumento_limite (
    id                 UUID        NOT NULL,
    cliente_id         VARCHAR(20) NOT NULL,
    conta_id           VARCHAR(20) NOT NULL,
    originador_id      VARCHAR(64) NOT NULL,
    origem_solicitacao VARCHAR(20) NOT NULL,
    canal_manifestacao VARCHAR(20) NOT NULL,
    observacao         VARCHAR(500),
    status             VARCHAR(30) NOT NULL,
    correlation_id     UUID        NOT NULL,
    efetivacao_id      UUID,
    registrada_em      TIMESTAMPTZ NOT NULL,
    atualizada_em      TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_solicitacao_aumento_limite PRIMARY KEY (id),
    CONSTRAINT uk_solicitacao_efetivacao_id UNIQUE (efetivacao_id),
    CONSTRAINT ck_solicitacao_origem CHECK (origem_solicitacao = 'CLIENTE'),
    CONSTRAINT ck_solicitacao_canal CHECK (canal_manifestacao IN ('PRESENCIAL', 'TELEFONE', 'CANAL_DIGITAL')),
    CONSTRAINT ck_solicitacao_status CHECK (status IN ('SOLICITADA', 'AGUARDANDO_EFETIVACAO',
        'EFETIVACAO_INDETERMINADA', 'EFETIVADA', 'REJEITADA', 'FALHA_EFETIVACAO')),
    CONSTRAINT ck_solicitacao_observacao CHECK (observacao IS NULL OR btrim(observacao) <> '')
);

-- A garantia real de "no maximo uma SolicitacaoAumentoLimite nao terminal por ContaCorrente"
-- (spec, secao "Unicidade nao terminal por ContaCorrente"; AC10): o pre-check em memoria da
-- aplicacao existe so para produzir um 409 compreensivel no caminho normal, mas quem decide sob
-- concorrencia real e este indice. O predicado IN (...) *e* a definicao operacional de "nao
-- terminal" -- os tres estados aqui sao exatamente os tres que StatusSolicitacaoAumentoLimite.
-- isTerminal() devolve false para hoje.
CREATE UNIQUE INDEX uk_solicitacao_nao_terminal_por_conta ON solicitacao_aumento_limite (conta_id)
    WHERE status IN ('SOLICITADA', 'AGUARDANDO_EFETIVACAO', 'EFETIVACAO_INDETERMINADA');

COMMENT ON TABLE solicitacao_aumento_limite IS
    'Agregado central de Credito (CONTEXT.md de Credito): pedido de aumento do LimiteChequeEspecial de uma ContaCorrente. clienteId/contaId/originadorId respondem "quem podia operar" -- deliberadamente fora de contexto_decisao_credito (AC33).';
COMMENT ON COLUMN solicitacao_aumento_limite.observacao IS
    'Opcional por contrato (AC27). ManifestacaoCliente ja normaliza vazia-apos-trim para ausencia antes de chegar aqui -- o CHECK e uma segunda linha de defesa contra string vazia gravada por engano.';
COMMENT ON COLUMN solicitacao_aumento_limite.efetivacao_id IS
    'So passa a existir quando a DecisaoCredito e APROVADA (TX2) -- permanece NULL em SOLICITADA e em REJEITADA. Unico junto com nao-nulo: dois EfetivacaoId nunca colidem.';
COMMENT ON COLUMN solicitacao_aumento_limite.status IS
    'StatusSolicitacaoAumentoLimite (ADR-0010 e sua emenda). A maquina de transicoes completa vive em Java (S1); este CHECK so garante o dominio de valor.';

-- Fotografia imutavel dos fatos considerados na submissao (ADR-0006, CONTEXT.md de Credito).
-- 1:1 com solicitacao_aumento_limite -- PK e tambem FK. NENHUM caso de uso deste ticket (nem de
-- nenhum futuro) faz UPDATE nesta tabela: se um fato precisar mudar, isso e uma NOVA submissao,
-- nunca a reescrita de uma decisao ja congelada.
CREATE TABLE contexto_decisao_credito (
    solicitacao_id                   UUID        NOT NULL,
    limite_cheque_especial_vigente   BIGINT      NOT NULL,
    situacao_conta                   VARCHAR(20) NOT NULL,
    classificacao_risco_credito_base VARCHAR(10) NOT NULL,
    limite_solicitado                BIGINT      NOT NULL,
    incremento_solicitado            BIGINT      NOT NULL,
    versao_politica_credito          VARCHAR(20) NOT NULL,
    capturado_em                     TIMESTAMPTZ NOT NULL,
    dados_credito_core_fonte         VARCHAR(40) NOT NULL,
    dados_credito_core_consultado_em TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_contexto_decisao_credito PRIMARY KEY (solicitacao_id),
    CONSTRAINT fk_contexto_decisao_credito_solicitacao FOREIGN KEY (solicitacao_id)
        REFERENCES solicitacao_aumento_limite (id),
    CONSTRAINT ck_contexto_situacao CHECK (situacao_conta IN ('REGULAR', 'IRREGULAR')),
    CONSTRAINT ck_contexto_risco CHECK (classificacao_risco_credito_base IN ('BAIXO', 'MEDIO', 'ALTO')),
    CONSTRAINT ck_contexto_vigente CHECK (limite_cheque_especial_vigente >= 0),
    CONSTRAINT ck_contexto_solicitado CHECK (limite_solicitado > 0),
    CONSTRAINT ck_contexto_incremento CHECK (incremento_solicitado > 0
        AND incremento_solicitado = limite_solicitado - limite_cheque_especial_vigente)
);

COMMENT ON TABLE contexto_decisao_credito IS
    'Fotografia imutavel (ADR-0006): nenhum caso de uso faz UPDATE nesta tabela -- uma linha e escrita uma unica vez em TX1 e nunca mais tocada. PK = FK para solicitacao_aumento_limite (relacao 1:1).';
COMMENT ON COLUMN contexto_decisao_credito.versao_politica_credito IS
    'A VersaoPoliticaCredito CAPTURADA nesta submissao (D5, plano #0003) -- nao a vigente no instante da decisao. MotorDecisaoCredito.decidir resolve por este valor, inclusive na retomada (AC18): mudar a politica vigente depois de TX1 nunca reescreve o significado desta decisao.';
COMMENT ON COLUMN contexto_decisao_credito.dados_credito_core_consultado_em IS
    'Instante em que ESTA plataforma capturou os fatos do CoreLegado com sucesso -- nao e a data em que o proprio host atualizou o limite (docs/contextos/credito/CONTEXT.md, secao "Sobre procedencia"). Essa segunda informacao, quando existir, fica encapsulada na ACL e nunca e derivada deste campo.';
COMMENT ON COLUMN contexto_decisao_credito.dados_credito_core_fonte IS
    'Identificacao LOGICA da fonte dos fatos externos (o sistema de onde vieram), nunca URL, host ou porta (DadosCreditoCore).';

-- Decisao com consequencia formal sobre a solicitacao (CONTEXT.md de Credito). PK = FK: no maximo
-- uma DecisaoCredito por SolicitacaoAumentoLimite, e e essa unicidade -- somada ao FOR UPDATE
-- NOWAIT em TX2 -- que impede duas decisoes concorrentes para a mesma solicitacao (plano #0003,
-- secao "Retomada de SOLICITADA").
CREATE TABLE decisao_credito (
    solicitacao_id           UUID        NOT NULL,
    resultado                VARCHAR(20) NOT NULL,
    motivo                   VARCHAR(40) NOT NULL,
    versao_politica_credito  VARCHAR(20) NOT NULL,
    decidida_em              TIMESTAMPTZ NOT NULL,
    autor_tipo               VARCHAR(20) NOT NULL,
    autor_id                 VARCHAR(64) NOT NULL,

    CONSTRAINT pk_decisao_credito PRIMARY KEY (solicitacao_id),
    CONSTRAINT fk_decisao_credito_solicitacao FOREIGN KEY (solicitacao_id)
        REFERENCES solicitacao_aumento_limite (id),
    CONSTRAINT ck_decisao_resultado CHECK (resultado IN ('APROVADA', 'REJEITADA')),
    CONSTRAINT ck_decisao_motivo CHECK (motivo IN ('DENTRO_DA_POLITICA_AUTOMATICA', 'CONTA_NAO_ELEGIVEL',
        'PERFIL_RISCO_INCOMPATIVEL', 'FORA_DA_POLITICA_AUTOMATICA')),
    CONSTRAINT ck_decisao_autor CHECK (autor_tipo IN ('HUMANO', 'SISTEMA'))
);

COMMENT ON TABLE decisao_credito IS
    'DecisaoCredito (CONTEXT.md de Credito). Neste ticket, sempre autor_tipo=SISTEMA / autor_id=MOTOR_DECISAO_CREDITO -- decisao humana e ParecerCredito pertencem a slices futuros.';
COMMENT ON COLUMN decisao_credito.versao_politica_credito IS
    'Copia da versao efetivamente aplicada por MotorDecisaoCredito.decidir -- sempre igual a contexto_decisao_credito.versao_politica_credito da mesma solicitacao, registrada aqui tambem porque a decisao e o registro autoritativo de "sob qual versao foi decidido" (CONTEXT.md de Credito, PoliticaCredito).';

-- Registro de idempotencia por (originadorId, Idempotency-Key) -- mecanismo tecnico de submissao,
-- nao vocabulario do glossario de Credito (RegistroIdempotencia, application/port/out). Escrito
-- uma UNICA vez dentro de TX1 e nunca atualizado depois: nao existe caso de uso que faca UPDATE
-- ou DELETE nesta tabela.
CREATE TABLE registro_idempotencia (
    originador_id   VARCHAR(64) NOT NULL,
    idempotency_key UUID        NOT NULL,
    fingerprint     CHAR(64)    NOT NULL,
    solicitacao_id  UUID        NOT NULL,
    criado_em       TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_registro_idempotencia PRIMARY KEY (originador_id, idempotency_key),
    CONSTRAINT fk_registro_idempotencia_solicitacao FOREIGN KEY (solicitacao_id)
        REFERENCES solicitacao_aumento_limite (id)
);

COMMENT ON TABLE registro_idempotencia IS
    'Escopo de unicidade originadorId + Idempotency-Key (nao a key isolada). E esta PK -- atingida DEPOIS do indice de nao-terminal por conta em TX1 -- que faz a releitura pos-conflito ser necessaria para classificar corretamente uma corrida na mesma conta como idempotencia (plano #0003, "Classificacao apos rollback").';
COMMENT ON COLUMN registro_idempotencia.fingerprint IS
    'SHA-256 hex (64 chars) do comando normalizado (FingerprintCanonico) -- nunca dos bytes JSON crus.';

-- D2 (plano #0003): o schema garante que a intencao de efetivacao nasce completa -- colunas
-- tipadas, sem "payload" generico, sem abstracao de transporte ou destino. Ninguem consome esta
-- tabela neste ticket; o dispatcher nasce em #0004.
CREATE TABLE outbox_mensagem (
    message_id                              UUID        NOT NULL,
    tipo                                    VARCHAR(40) NOT NULL,
    destino                                 VARCHAR(40) NOT NULL,
    solicitacao_id                          UUID        NOT NULL,
    efetivacao_id                           UUID        NOT NULL,
    conta_id                                VARCHAR(20) NOT NULL,
    limite_cheque_especial_vigente_esperado BIGINT      NOT NULL,
    limite_solicitado                       BIGINT      NOT NULL,
    correlation_id                          UUID        NOT NULL,
    criado_em                               TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_outbox_mensagem PRIMARY KEY (message_id),
    CONSTRAINT fk_outbox_mensagem_solicitacao FOREIGN KEY (solicitacao_id)
        REFERENCES solicitacao_aumento_limite (id),
    CONSTRAINT uk_outbox_solicitacao UNIQUE (solicitacao_id),
    CONSTRAINT uk_outbox_efetivacao UNIQUE (efetivacao_id),
    CONSTRAINT ck_outbox_tipo CHECK (tipo = 'EfetivarLimite'),
    CONSTRAINT ck_outbox_destino CHECK (destino = 'CORE_LEGADO'),
    CONSTRAINT ck_outbox_valores CHECK (limite_cheque_especial_vigente_esperado >= 0
        AND limite_solicitado > limite_cheque_especial_vigente_esperado)
);

COMMENT ON TABLE outbox_mensagem IS
    'Intencao duravel de EfetivacaoLimite (ADR-0009), gravada na mesma transacao da decisao (TX2) quando APROVADA. uk_outbox_solicitacao + uk_outbox_efetivacao sao a garantia fisica de "exatamente uma intencao por decisao aprovada" (AC1).';
COMMENT ON COLUMN outbox_mensagem.limite_cheque_especial_vigente_esperado IS
    'O LimiteChequeEspecialVigente CONGELADO no ContextoDecisaoCredito no momento da decisao -- a precondicao que o CoreLegado usara para recusar aplicar por cima de um estado que ja mudou (CONTEXT.md de Credito, EfetivacaoLimite).';

-- Trilha de historico funcional append-only (spec, secao "Historico funcional"). id tecnico
-- (ordem de insercao, estavel); fato_id e a identidade LOGICA e deterministica do fato causador --
-- e a UNIQUE constraint sobre ela, e nao o id tecnico, que deduplica sob replay/redelivery.
CREATE TABLE historico_solicitacao (
    id             BIGINT GENERATED ALWAYS AS IDENTITY,
    solicitacao_id UUID         NOT NULL,
    fato_id        VARCHAR(120) NOT NULL,
    tipo_fato      VARCHAR(40)  NOT NULL,
    ator_tipo      VARCHAR(20)  NOT NULL,
    ator_id        VARCHAR(64)  NOT NULL,
    ocorrido_em    TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_historico_solicitacao PRIMARY KEY (id),
    CONSTRAINT fk_historico_solicitacao_solicitacao FOREIGN KEY (solicitacao_id)
        REFERENCES solicitacao_aumento_limite (id),
    CONSTRAINT uk_historico_fato UNIQUE (fato_id),
    CONSTRAINT ck_historico_ator CHECK (ator_tipo IN ('HUMANO', 'SISTEMA')),
    CONSTRAINT ck_historico_tipo CHECK (tipo_fato IN ('SOLICITACAO_REGISTRADA', 'DECISAO_AUTOMATICA_REGISTRADA'))
);

COMMENT ON TABLE historico_solicitacao IS
    'Trilha funcional append-only por SolicitacaoAumentoLimite -- NAO e Event Sourcing, nao reconstroi o agregado (spec, secao "Historico funcional"). tipo_fato restrito aos dois fatos que #0003 produz; fatos de efetivacao pertencem a #0004+ e nao existem aqui ainda (ADR-0010).';
COMMENT ON COLUMN historico_solicitacao.fato_id IS
    'Identidade LOGICA e deterministica do fato causador (ex.: "SOLICITACAO:"+solicitacaoId, "DECISAO:"+solicitacaoId) -- a uk_historico_fato sobre esta coluna, e nao o id tecnico, e quem deduplica sob replay/redelivery.';
COMMENT ON COLUMN historico_solicitacao.id IS
    'GENERATED ALWAYS AS IDENTITY: a sequence subjacente exige GRANT USAGE, SELECT ON SEQUENCES para credito_app, alem do GRANT usual em tabelas (infra/postgres-init/02-credito.sh) -- sem isso, INSERT nesta tabela falharia mesmo com privilegio de escrita na tabela em si.';
