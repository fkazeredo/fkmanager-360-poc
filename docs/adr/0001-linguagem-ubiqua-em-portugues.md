# Linguagem ubíqua em português, terminologia técnica em inglês

Todo conceito que expressa negócio é nomeado em pt-BR: agregados, value objects, casos de uso,
eventos e comandos de domínio, estados, regras, endpoints, tabelas, colunas, mensagens assíncronas
e nomes de deployables que representam capacidades de negócio. Terminologia técnica consolidada
permanece em inglês, porque é o nome próprio da tecnologia: `Repository`, `Adapter`, `Controller`,
`Consumer`, `Producer`, `Configuration`, `DTO`, `REST`, `Kafka`, `RabbitMQ`, `OAuth2`, `BFF`,
`Outbox`.

A mistura dentro de um mesmo identificador é esperada e correta, não um cheiro:
`SolicitacaoAumentoLimiteRepository`, `JpaSolicitacaoAumentoLimiteAdapter`,
`SolicitacaoAumentoLimiteController`. O deployable `bff-gerente` é preferível a inventar uma
tradução para BFF.

A razão é que o domínio é bancário brasileiro e termos como *alçada* e *cheque especial* não têm
tradução honesta — "approval limit" e "overdraft" achatam distinções que o negócio realmente faz.
Traduzir o domínio significaria criar duas linguagens paralelas, uma falada pelo negócio em
português e outra inventada pelos desenvolvedores em inglês, que é exatamente o que uma linguagem
ubíqua existe para impedir.

## Consequências

Identificadores são ASCII, sem acento e sem cedilha (`Alcada`, não `Alçada`; `AvaliacaoRisco`, não
`AnáliseRisco`), para evitar identificadores Unicode em Java e manter URLs, nomes de tópico e
caminhos de arquivo portáveis. Prosa, documentação e este conjunto de ADRs mantêm a acentuação.

Documentação de arquitetura também é escrita em português: redigir ADRs em inglês sobre um domínio
em pt-BR reintroduziria a cisão que esta decisão elimina.

Renomear depois atingiria todo tipo de domínio em todos os serviços, então isto é uma porta de
mão única na prática.
