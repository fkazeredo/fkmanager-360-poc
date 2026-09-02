# A decisão é tomada sobre uma fotografia imutável, não sobre dados vivos

Score, renda, saldo médio, movimentação e exposição mudam com o tempo. O `ContextoDecisaoCredito`
congela, no momento da submissão, os fatos considerados, junto com a `versaoPoliticaCredito`
aplicada — e é imutável depois de capturado. Quando há avaliação de risco, preserva também a
versão do modelo, o `ResultadoAvaliacaoRisco` e o instante da avaliação.

Sem isso a decisão não é reproduzível, e a capacidade "consultar o histórico de decisões e
operações" vira ficção: reavaliar uma decisão de seis meses atrás com os dados de hoje responde
uma pergunta diferente da que foi feita.

Congelar **não** significa copiar dados brutos. Gravam-se os indicadores derivados efetivamente
utilizados, o período calculado e a procedência de cada informação — `saldoMedio90Dias`, e não
`List<MovimentacaoConta> ultimos90Dias`. Copiar centenas ou milhares de lançamentos para dentro do
agregado apenas por auditoria inflaria o agregado sem melhorar a rastreabilidade.

## Consequências

A pergunta que o snapshot precisa responder no futuro é: *com quais informações e sob qual versão
das regras esta decisão foi tomada?*

Se qualquer informação obrigatória não puder ser obtida durante a montagem, a
`SolicitacaoAumentoLimite` **não é persistida** e a requisição falha explicitamente. Não existe
solicitação com contexto incompleto, porque aceitá-la e preencher depois criaria exatamente o
estado inconsistente que o conceito existe para impedir. Como a nova tentativa precisa ser segura,
há requisito concreto de idempotência já na submissão.

A representação física — tabela própria, JSON ou outra — é decisão de persistência e não deve
condicionar a modelagem conceitual.
