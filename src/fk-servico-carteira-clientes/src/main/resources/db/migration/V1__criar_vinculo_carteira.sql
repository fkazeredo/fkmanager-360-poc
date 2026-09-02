-- CarteiraClientes possui armazenamento privado proprio (ADR-0014). Esta e a unica tabela que o
-- comportamento vertical do ticket #0001 exige: a associacao GerenteRelacionamento <-> Cliente que
-- da nome ao contexto. ContaCorrente, direito de atendimento por conta e qualquer coisa de
-- Credito nascem em tickets posteriores.
--
-- cliente_id referencia o mesmo identificador que a ACL deste servico traduz de/para o COD-CLI
-- host-centric do simulador (ADR-0004, ADR-0005) -- aqui ele vive sem zero-padding; a formatacao
-- host e responsabilidade exclusiva da ACL, na fronteira de saida.

CREATE TABLE vinculo_carteira (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    gerente_id  VARCHAR(64) NOT NULL,
    cliente_id  VARCHAR(20) NOT NULL,
    criado_em   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uk_vinculo_carteira_gerente_cliente UNIQUE (gerente_id, cliente_id)
);

COMMENT ON TABLE vinculo_carteira IS
    'Associacao atual GerenteRelacionamento <-> Cliente. CarteiraClientes e a autoridade sobre ela (CONTEXT-MAP.md).';
COMMENT ON COLUMN vinculo_carteira.gerente_id IS
    'Identidade do GerenteRelacionamento, derivada do claim "sub" do token autenticado.';
COMMENT ON COLUMN vinculo_carteira.cliente_id IS
    'Identificador do Cliente nesta associacao. Sem zero-padding -- a ACL formata para COD-CLI na fronteira de saida.';

-- A paginacao ordena por id (ordem de insercao, estavel); a unique constraint acima ja cobre
-- consultas por gerente_id com boa seletividade, sem indice adicional necessario neste volume.
