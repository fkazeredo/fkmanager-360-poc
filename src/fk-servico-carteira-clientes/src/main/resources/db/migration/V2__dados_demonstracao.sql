-- Dados de demonstracao da POC. Este projeto nao tem ambiente de "producao" real (CONTEXT.md:
-- "ambiente bancario ficticio, nenhum dado de cliente real") -- semear a carteira aqui, em vez de
-- atras de um profile, mantem a POC executavel de ponta a ponta com um unico comando.
--
-- cliente_id corresponde, sem zero-padding, ao COD-CLI semeado em
-- simulador-core-legado/.../BaseClientesLegado (ex.: cliente_id '1' <-> COD-CLI '0000000001'),
-- para que a jornada vertical completa seja coerente.

-- Carteira do gerente A: sete clientes, o suficiente para forcar paginacao com tamanho de pagina 5.
INSERT INTO vinculo_carteira (gerente_id, cliente_id) VALUES
    ('gerente.a', '1'),
    ('gerente.a', '2'),
    ('gerente.a', '3'),
    ('gerente.a', '4'),
    ('gerente.a', '5'),
    ('gerente.a', '6'),
    ('gerente.a', '7');

-- Carteira do gerente B: tres clientes, exclusivos -- prova de segregacao (AC22/AC9 da spec).
INSERT INTO vinculo_carteira (gerente_id, cliente_id) VALUES
    ('gerente.b', '101'),
    ('gerente.b', '102'),
    ('gerente.b', '103');

-- COD-CLI '0000000999' (cliente '999') existe no simulador mas deliberadamente sem vinculo aqui:
-- prova que a existencia no Core, isolada, nao concede acesso -- so a associacao concede.
