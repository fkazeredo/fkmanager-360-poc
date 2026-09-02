# CarteiraClientes

Glossário deste bounded context, materializado pelo ticket #0001 (slice 1). Vocabulário
transversal — **Atores** — permanece no `CONTEXT.md` raiz por ser compartilhado entre contextos
(`docs/agents/domain.md`); o mapa geral está em `CONTEXT-MAP.md`.

A linguagem ubíqua é **pt-BR** (ADR-0001). Identificadores não levam acento nem cedilha; a prosa
deste arquivo leva.

Contexto responsável por responder quem é o cliente, quais contas ele possui, e qual gerente tem
direito de atendê-lo: cadastro, vínculo e relacionamento — os dados de que a experiência do gerente
precisa para chegar até a conta certa.

**Não é dono do LimiteChequeEspecial nem de nenhum outro dado financeiro da conta.** O
LimiteChequeEspecialVigente é consultado por Credito, pela ACL própria daquele contexto (ADR-0004).
Quando uma tela precisar exibir cliente, conta e limite ao mesmo tempo, quem compõe é o
`bff-gerente`, e o resultado é modelo de apresentação — não agregado, não contexto (ADR-0013).

**CarteiraClientes**:
Conjunto de Clientes sob responsabilidade de um GerenteRelacionamento. A associação pertence ao
fk-manager-360; os dados mestres do Cliente, não.
_Evitar_: Base de clientes, Book, Portfólio

**ContaCorrente**:
Conta de um Cliente. Neste contexto é essencialmente a conta que pertence a determinado Cliente e
que determinado gerente tem direito de atender. Não é a mesma coisa que `ContaCorrente` em
`Movimentacoes` (vocabulário ainda provisório, no `CONTEXT.md` raiz) — ali é referência para
lançamentos, períodos, saldos e reconciliação.

Deployable: `fk-servico-carteira-clientes`. Migrations são embutidas no próprio serviço (Flyway,
ADR-0014 emendada) — não existe deployable separado para elas. `fk-bff-gerente` e `fk-app-gerente`
não são bounded context — são fronteira web e apresentação (`CONTEXT-MAP.md`).
