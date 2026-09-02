# Mapa de contextos: Credito é o core, Risco é supporting, auditoria e notificações são transversais

Os bounded contexts de negócio são `IdentidadeEAcesso`, `CarteiraClientes`, `Movimentacoes`,
`Credito` e `Risco`. `Credito` é o core domain da POC; `Risco` é um supporting domain
especializado.

`Credito` e `Risco` são contextos separados porque fazem coisas diferentes: o
`MotorDecisaoCredito` **enquadra** — aplica a política e conclui, quando a política permite —
enquanto a `AvaliacaoRisco` **pontua**, com processamento mais caro e assíncrono, e não aprova
crédito. O contexto de Risco produz informação especializada para que Credito prossiga; a
integração entre os dois é explícita e nunca por compartilhamento do modelo interno.

`Movimentacoes` é contexto próprio, e não um deployable separado de `CarteiraClientes` por motivo
de volume. Ele fala uma língua que ninguém mais fala — lote de importação, arquivo de movimento,
data de movimento, reconciliação, reprocessamento. O fato de os dois contextos conhecerem uma
`ContaCorrente` não os obriga a compartilhar modelo: em `CarteiraClientes` a conta é a conta que
pertence a um cliente e que um gerente pode atender; em `Movimentacoes` ela é referência para
lançamentos, períodos, saldos e reconciliação. **Entidades de domínio Java não são compartilhadas
entre contextos.**

Auditoria e notificações **não são bounded contexts**: não possuem agregado, invariante nem
decisão. São capacidades transversais que consomem fatos. Podem existir como deployables
independentes, com arquitetura muito mais simples, e não devem receber DDD tático artificialmente.

## Consequências

O mapa não é o organograma dos deployables. Nove processos podem servir cinco contextos mais duas
capacidades transversais, e inflar o mapa para dizer "temos nove contextos" ensinaria exatamente a
coisa errada.
