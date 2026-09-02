# Alçada por perfil, em dois eixos; risco roteia, alçada autoriza

A `AlcadaAprovacao` mede em dois eixos simultâneos: o limite absoluto resultante e o incremento
solicitado. Apenas o valor absoluto deixaria passar um salto de R$ 500 para R$ 4.900 sem
escrutínio; apenas o incremento ignoraria exposição acumulada. A alçada responde
`podeAprovar(limiteVigente, limiteSolicitado)` considerando os dois.

Os números não ficam no usuário. Um `PerfilAlcadaAprovacao` nomeado define uma `AlcadaAprovacao`,
e o ator recebe um perfil. Isso é melhor do que codificar `if role == SUPERVISOR`, e melhor do que
gravar números arbitrários soltos em cada usuário — e é o que permite acrescentar níveis de
aprovador sem mudar o modelo.

**Risco e alçada permanecem ortogonais: risco determina roteamento e elegibilidade; alçada
determina autoridade.** Uma SolicitacaoAumentoLimite pode exigir decisão humana por causa do risco,
e ainda assim é preciso verificar se aquele aprovador tem alçada suficiente para o valor. A
classificação de risco não entra dentro do value object de alçada: se um dia surgir regra do tipo
"casos excepcionais de risco exigem nível superior", ela se expressa como política de roteamento e
aprovação, e não transformando `AlcadaAprovacao` num objeto que conhece toda a política de crédito.

## Consequências

Uma decisão humana só é válida quando o aprovador possui alçada suficiente, o aprovador não é o
originador (ADR-0007), a SolicitacaoAumentoLimite está em estado que permite decisão, e todas as
análises obrigatórias foram concluídas.

A decisão humana registra qual perfil e qual alçada estavam efetivamente vigentes quando foi
tomada — perfis mudam, e sem isso a auditoria de uma decisão antiga fica sem base.
