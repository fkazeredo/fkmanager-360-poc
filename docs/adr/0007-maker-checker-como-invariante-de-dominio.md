# Maker-checker é invariante de domínio; autorização de recurso não vive nas claims

O GerenteRelacionamento origina a solicitação a pedido do Cliente e não pode aprovar a solicitação
que ele próprio originou. Isto não é uma afirmação de que gerentes de banco não possuem alçada de
crédito: é segregação de funções adotada deliberadamente como regra de governança desta POC.

A regra vive **dentro do agregado**, não em guard do Angular, não no `bff-gerente`, não em
interceptor de segurança. `aprovar(ator)` recusa quando o ator é o originador, valha a entrada
Angular, REST, mensagem, batch, teste ou futura interface administrativa. Uma regra de negócio
imposta na borda desaparece no primeiro caminho de entrada que alguém esquecer de proteger.

O domínio não conhece JWT, OAuth nem Spring Security. O adapter converte a identidade autenticada
em um conceito de aplicação — `AtorId` ou equivalente — e o domínio raciocina sobre atores, não
sobre tokens.

## Autorização por recurso

A carteira do gerente **não** vai nas claims do token. Claims carregam identidade, papéis e
escopos; a pergunta "este gerente pode acessar este cliente?" é autorização sobre um recurso de
negócio, e a resposta muda sem que o token mude.

`CarteiraClientes` é a autoridade sobre a associação atual GerenteRelacionamento ↔ Carteira ↔
Cliente e verifica esse direito ao ser acessado. O `bff-gerente` pode ocultar opções e barrar
chamadas obviamente indevidas, mas nunca é o único enforcement point: cada backend protege seus
próprios recursos.

## Consequências

Acesso ao cliente atual e acesso ao histórico de uma SolicitacaoAumentoLimite **não são a mesma
regra**. Se o cliente mudar de carteira depois que o gerente originou uma solicitação, ainda é
preciso decidir se ele continua enxergando aquele histórico — e essa regra não deve ser resolvida
implicitamente por claims. Por isso a solicitação registra o `originadorId` e o contexto
organizacional vigente no momento da origem.

Analistas e supervisores acessam solicitações segundo suas filas, permissões e alçadas, não segundo
a carteira original do gerente.
