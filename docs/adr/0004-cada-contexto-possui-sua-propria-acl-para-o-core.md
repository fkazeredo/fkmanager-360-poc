# Cada contexto integra o CoreLegado pela sua própria Anti-Corruption Layer

A integração com o legado é propriedade semântica de cada bounded context. Um contexto pode
acessar diretamente uma capacidade legada quando essa capacidade pertence semanticamente ao seu
domínio, mas deve fazê-lo exclusivamente através da sua própria porta e da sua própria
Anti-Corruption Layer.

Assim, `CarteiraClientes` consulta cliente, vínculo cliente-conta e identificação de conta
corrente. `Credito` consulta o limite vigente de cheque especial e comanda a efetivação e a
consulta de status dessa efetivação — ou seja, `servico-credito` fala diretamente com o Core.
`Movimentacoes`, quando surgir, será responsável pelas integrações de lançamentos, arquivos
noturnos, importação e reconciliação.

Duas alternativas foram rejeitadas. **Eleger um serviço moderno como gateway universal do Core**
transformaria `servico-carteira-clientes` em intermediário técnico para dados que não pertencem ao
seu domínio, acoplando-o a requisitos alheios. **Criar um `servico-integracao-core` genérico**
correria o risco de virar um ESB interno sem semântica própria, concentrando conhecimento de todos
os domínios num lugar que não é dono de nenhum.

## Consequências

Mais de um contexto conhece o contrato legado — mas cada um conhece apenas a fatia que lhe diz
respeito, e sempre atrás de uma ACL. O domínio de cada contexto permanece totalmente isolado do
contrato do host.

A montagem do `ContextoDecisaoCredito` é orquestrada por `Credito` a partir de fontes distintas:
`CarteiraClientes` para identidade e relacionamento, a ACL própria de Credito para o limite
vigente, e futuramente `Movimentacoes` para indicadores financeiros. O `ContextoDecisaoCredito` não
precisa ter fonte física única.
