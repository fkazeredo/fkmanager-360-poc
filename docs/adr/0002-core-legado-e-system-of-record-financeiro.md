# O CoreLegado é o system of record do estado financeiro

O `simulador-core-legado` representa de forma simplificada o conjunto de systems of record legados
da instituição — não se assume que exista literalmente uma única aplicação mainframe dona de tudo.
Ele é autoritativo para: cadastro mestre do cliente, conta corrente, saldo, lançamentos
financeiros, posição financeira, limite vigente de cheque especial, e a efetivação operacional da
alteração desse limite.

O fk-manager-360 **não é o ledger do banco e não substitui o core banking**. Ele é autoritativo
apenas para o processo novo: carteira de atendimento do gerente, solicitação de aumento de limite,
dados capturados especificamente para a solicitação, avaliação de risco, pareceres, decisões,
alçadas utilizadas, workflow, auditoria do processo e estado da integração com o legado.

Projeções locais existem para consulta eficiente — a tabela de movimentações do
`servico-movimentacoes` é uma projeção operacional, não o livro contábil nem a fonte oficial das
movimentações. Essa distinção deve aparecer no modelo, e não apenas na documentação.

## Consequências

**Solicitação aprovada não significa limite efetivado.** Uma DecisaoCredito aprovada produz uma
instrução de alteração para o CoreLegado; o LimiteChequeEspecialVigente só muda quando o Core
confirma. Nenhuma tela, relatório ou resposta de API pode afirmar que o Cliente possui um novo
limite porque a decisão interna foi favorável — o valor exibido como limite vigente sempre vem do
sistema autoritativo.

Essa separação também é a origem legítima da integração online, do processamento batch, das
staging tables e da reconciliação: a verdade mora em outro lugar e precisa ser trazida, e não
porque a lista de tecnologias pedia.
