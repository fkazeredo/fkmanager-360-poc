---
title: Slice 1 — Straight-Through Approval
state: open
triage: ready-for-agent
created: 2026-09-02
---

# Slice 1 — Straight-Through Approval

Primeira jornada vertical do `fk-manager-360`: da autenticação do `GerenteRelacionamento` até a
efetivação do aumento de `LimiteChequeEspecial` no `CoreLegado`, com decisão automática.

Linguagem ubíqua em pt-BR (ADR-0001). Esta spec define contratos **semanticamente**; nomes finais de
classes, packages, paths, DDL, índices e os `COD-RET` fictícios do simulador pertencem aos tickets.

## Problem Statement

Um `GerenteRelacionamento` recebe do `Cliente` o pedido de aumento do limite de cheque especial e hoje
não tem por onde registrá-lo: não existe caminho que vá do pedido manifestado até a alteração efetivada
no `CoreLegado`, com decisão registrada, rastreável e reproduzível.

Fazer isso à mão tem três problemas que a POC quer expor:

1. **A decisão precisa ser reproduzível.** Saldo, situação da conta e risco mudam. Uma decisão tomada
   hoje precisa continuar explicável amanhã com os fatos de hoje, e não com os de amanhã.
2. **Aprovar não é efetivar.** O `CoreLegado` é o system of record do limite (ADR-0002). Entre a decisão
   e a confirmação do Core existe uma janela real, que falha de formas diferentes — e nenhuma tela pode
   afirmar que o `Cliente` tem limite novo antes dessa confirmação.
3. **Falha de comunicação não é falha de negócio.** Perder a resposta do Core não é evidência de que a
   efetivação não aconteceu. Registrar isso como falha gravaria um fato possivelmente falso num processo
   financeiro.

Do lado do projeto, este é o primeiro slice: nenhum contexto está materializado em código, não existe
`src/`, e ele precisa entregar comportamento observável de ponta a ponta criando apenas a infraestrutura
que esse comportamento exige (ADR-0010).

## Solution

O `GerenteRelacionamento` autentica-se, abre sua `CarteiraClientes`, escolhe um `Cliente` e uma
`ContaCorrente`, e vê o `LimiteChequeEspecialVigente` que o `CoreLegado` reconhece agora. Registra a
`ManifestacaoCliente` — por qual canal o pedido chegou, mais observação opcional — e o `LimiteSolicitado`.

Na submissão, `Credito` verifica o direito de atendimento em `CarteiraClientes`, lê do `CoreLegado` os
`DadosCreditoCore` pela sua própria ACL, congela tudo num `ContextoDecisaoCredito` imutável e aplica a
`PoliticaCredito v1`. A decisão é automática e sempre conclusiva neste slice: `APROVADA` ou `REJEITADA`,
com um `MotivoDecisaoCredito` estável.

Rejeição é terminal e nunca gera efetivação. Aprovação transiciona para `AGUARDANDO_EFETIVACAO` e grava,
na mesma transação **da decisão**, a intenção durável de efetivação com um `EfetivacaoId` próprio. Um dispatcher entrega
a instrução ao `CoreLegado`, que devolve um `ProtocoloCore`. O Core confirma o resultado por callback;
quando o callback não chega, um reconciliador pergunta. Os dois caminhos convergem no mesmo caso de uso
idempotente `RegistrarResultadoEfetivacao`.

Quando nem o callback nem a reconciliação produzem resposta autoritativa dentro da janela normal, a
solicitação entra em `EFETIVACAO_INDETERMINADA` — estado que afirma ignorância, não falha — e continua
recuperável por uma resposta posterior.

O gerente acompanha o processo pela listagem `MinhasSolicitacoesAumentoLimite` e pelo histórico funcional
de cada solicitação.

## User Stories

**Autenticação e sessão**

1. Como `GerenteRelacionamento`, quero autenticar-me com minhas credenciais corporativas, para que eu
   acesse a aplicação sem gerenciar mais uma senha.
2. Como `GerenteRelacionamento`, quero que meus tokens nunca cheguem ao browser, para que uma falha no
   frontend não exponha credenciais de acesso à plataforma.
3. Como `GerenteRelacionamento`, quero que minha sessão sobreviva ao restart de uma instância do
   `bff-gerente`, para que eu não seja deslogado por manutenção.
4. Como `GerenteRelacionamento`, quero encerrar minha sessão explicitamente, para que a estação
   compartilhada não fique acessível ao próximo usuário.
5. Como responsável pela plataforma, quero que cada serviço receba um token emitido para ele, para que o
   vazamento de um token alcance um serviço e não a plataforma inteira.

**Carteira, Cliente e ContaCorrente**

6. Como `GerenteRelacionamento`, quero ver a lista dos `Cliente`s da minha `CarteiraClientes`, para que eu
   chegue rapidamente a quem estou atendendo.
7. Como `GerenteRelacionamento`, quero que a listagem seja paginada, para que a carteira continue
   utilizável quando crescer.
8. Como `GerenteRelacionamento`, quero selecionar um `Cliente` e ver suas `ContaCorrente`s, para que eu
   escolha a conta certa antes de qualquer solicitação.
9. Como `GerenteRelacionamento`, quero ser impedido de acessar um `Cliente` que não é da minha carteira,
   para que a segregação de atendimento seja real e não apenas visual.
10. Como responsável pela governança, quero que esse impedimento seja verificado pelo backend dono da
    associação, para que ocultar um botão no Angular nunca seja o único controle.

**Limite vigente**

11. Como `GerenteRelacionamento`, quero ver o `LimiteChequeEspecialVigente` reconhecido pelo `CoreLegado`,
    para que eu converse com o `Cliente` sobre o valor real e não sobre uma projeção local.
12. Como `GerenteRelacionamento`, quero ver numa única tela o `Cliente`, a `ContaCorrente` e o limite
    vigente, para que eu não navegue entre telas durante o atendimento.
13. Como responsável pela arquitetura, quero que essa tela seja composta pelo `bff-gerente` a partir de
    dois contextos, para que nenhum deles precise conhecer dados que não são seus.

**Manifestação do Cliente**

14. Como `GerenteRelacionamento`, quero registrar por qual `CanalManifestacao` o `Cliente` pediu o
    aumento, para que fique evidente que o pedido partiu dele.
15. Como `GerenteRelacionamento`, quero acrescentar uma observação livre curta, para que o contexto do
    atendimento não se perca.
16. Como responsável pela governança, quero que a `OrigemSolicitacao` seja estabelecida pelo domínio e
    nunca aceita do browser, para que o sistema não possa ser levado a registrar origem falsa.

**Submissão**

17. Como `GerenteRelacionamento`, quero informar o `LimiteSolicitado` e submeter, para que o pedido do
    `Cliente` entre no processo formal.
18. Como `GerenteRelacionamento`, quero ser impedido de submeter um valor que não aumenta o limite, para
    que eu perceba o erro antes de gerar um processo inútil.
19. Como `GerenteRelacionamento`, quero que a submissão falhe se o limite mudou no `CoreLegado` depois que
    a tela carregou, para que eu não solicite um incremento diferente do que mostrei ao `Cliente`.
20. Como `GerenteRelacionamento`, quero que a tela se atualize e me deixe reenviar após essa falha, para
    que a correção seja um passo e não um recomeço.
21. Como responsável pela governança, quero que uma solicitação nunca seja persistida com contexto
    incompleto, para que não exista processo cuja decisão não possa ser explicada.

**Idempotência e duplicidade**

22. Como `GerenteRelacionamento`, quero que clicar duas vezes em submeter não crie duas solicitações, para
    que eu não gere trabalho duplicado por um clique nervoso.
23. Como `GerenteRelacionamento`, quero poder repetir a submissão após erro temporário de integração, para
    que uma indisponibilidade momentânea não me obrigue a refazer o formulário.
24. Como `GerenteRelacionamento`, quero que essa repetição, se a primeira tiver dado certo sem que eu
    soubesse, me devolva a mesma solicitação, para que eu não crie duas.
25. Como responsável pela plataforma, quero que a reutilização da mesma chave com dados diferentes seja
    recusada, para que o mecanismo de idempotência não silencie um bug de cliente.
26. Como `GerenteRelacionamento`, quero que uma nova manifestação legítima do `Cliente` gere nova
    solicitação mesmo com os mesmos valores, para que a idempotência não me impeça de trabalhar.

**Concorrência**

27. Como responsável pela governança, quero que uma `ContaCorrente` tenha no máximo uma
    `SolicitacaoAumentoLimite` não terminal, para que duas alterações concorrentes não disputem o mesmo
    limite no `CoreLegado`.
28. Como `GerenteRelacionamento`, quero ser informado com clareza quando já existe processo em andamento
    para aquela conta, para que eu saiba que devo aguardar e não que o sistema falhou.

**Contexto de decisão**

29. Como responsável pela governança, quero que os fatos usados na decisão sejam congelados no momento da
    submissão, para que a decisão continue reproduzível daqui a seis meses.
30. Como responsável pela governança, quero que o contexto guarde procedência e instante da consulta
    externa, para que se saiba de onde veio cada informação.
31. Como responsável pela governança, quero que o `IncrementoSolicitado` seja calculado e gravado, para
    que ele não seja recalculado no futuro contra um limite vigente diferente.
32. Como responsável pela governança, quero que a identidade do sujeito e a verificação do direito de
    atendimento fiquem **fora** do contexto de decisão, para que não se confunda "quem podia operar" com
    "com quais fatos se decidiu".

**Decisão automática**

33. Como `GerenteRelacionamento`, quero receber o resultado da decisão na própria submissão, para que eu
    responda ao `Cliente` durante o atendimento.
34. Como responsável pela governança, quero que toda decisão registre a versão da `PoliticaCredito`
    aplicada, para que mudar a política amanhã não reescreva o significado de uma decisão de ontem.
35. Como responsável pela governança, quero que conta em situação irregular seja rejeitada com motivo
    próprio, para que essa recusa não se confunda com recusa por valor.
36. Como responsável pela governança, quero que perfil de risco incompatível seja rejeitado com motivo
    próprio, para que a política tenha eixo de risco desde o primeiro slice.
37. Como responsável pela governança, quero que um caso que não se enquadra em nenhuma regra explícita
    seja rejeitado com `FORA_DA_POLITICA_AUTOMATICA`, para que a política tenha zona cinzenta declarada em
    vez de fingir cobrir tudo.
38. Como `GerenteRelacionamento`, quero que `FORA_DA_POLITICA_AUTOMATICA` não seja apresentado como
    problema de risco do `Cliente`, para que eu não transmita a ele uma informação falsa.
39. Como `GerenteRelacionamento`, quero que a rejeição seja terminal, para que eu saiba que não há nada a
    aguardar.

**Efetivação**

40. Como `GerenteRelacionamento`, quero que uma aprovação vire instrução durável ao `CoreLegado`, para que
    ela não se perca se o serviço cair logo depois de decidir.
41. Como `GerenteRelacionamento`, quero continuar vendo o limite antigo como vigente enquanto a efetivação
    não é confirmada, para que eu nunca prometa ao `Cliente` um limite que ele ainda não tem.
42. Como responsável pela governança, quero que a instrução carregue o limite vigente sobre o qual se
    decidiu, para que a alteração não seja aplicada por cima de um estado que mudou.
43. Como responsável pela governança, quero que a instrução tenha identidade de negócio própria, para que
    reenviá-la não aplique a alteração duas vezes.
44. Como responsável pela plataforma, quero que a entrega tenha retry curto e finito, para que uma falha
    transitória se resolva sozinha sem virar loop infinito.
45. Como responsável pela plataforma, quero que a perda da resposta de aceite não impeça a recuperação,
    para que a operação continue rastreável mesmo sem o `ProtocoloCore`.

**Callback**

46. Como responsável pela plataforma, quero que o `CoreLegado` confirme o resultado por callback
    autenticado, para que a conclusão seja rápida no caminho feliz.
47. Como responsável pela plataforma, quero que callback duplicado idêntico não produza efeito algum, para
    que a entrega at-least-once não corrompa o histórico.
48. Como responsável pela plataforma, quero que um callback que chegue antes do registro do aceite ainda
    encontre a operação, para que uma corrida de rede não quebre o processo.
49. Como responsável pela plataforma, quero que callback contraditório não reescreva estado terminal, para
    que a última mensagem a chegar não seja automaticamente a verdade.
50. Como responsável pela operação, quero que esse conflito gere anomalia observável e ainda assim seja
    reconhecido com `2xx`, para que a inconsistência seja investigável sem virar tempestade de redelivery.

**Reconciliação e indeterminação**

51. Como responsável pela plataforma, quero que efetivações paradas além do prazo sejam reconciliadas
    automaticamente, para que um callback perdido não deixe a solicitação parada em silêncio.
52. Como responsável pela plataforma, quero que o reconciliador apenas consulte e nunca reenvie a
    instrução, para que não existam dois caminhos de entrega concorrendo.
53. Como responsável pela plataforma, quero que a reconciliação seja segura sob execução concorrente, para
    que escalar o serviço não duplique trabalho.
54. Como responsável pela governança, quero que ausência de resposta autoritativa **não** seja registrada
    como falha, para que o sistema não afirme um fato que não conhece.
55. Como responsável pela operação, quero que essa situação entre em `EFETIVACAO_INDETERMINADA` com métrica
    e alerta, para que alguém saiba que existe operação sem desfecho.
56. Como responsável pela plataforma, quero que uma resposta posterior conclua uma operação indeterminada,
    para que o desfecho tardio ainda seja registrado corretamente.
57. Como responsável pela governança, quero que uma conta com operação indeterminada continue bloqueada
    para novas solicitações, para que não se autorize outra alteração sem saber o estado da anterior.

**Histórico e acompanhamento**

58. Como `GerenteRelacionamento`, quero listar as solicitações que originei, da mais recente para a mais
    antiga, para que eu retome o acompanhamento quando voltar à aplicação.
59. Como `GerenteRelacionamento`, quero ver o estado atual e o resultado da decisão de cada uma, para que
    eu identifique rapidamente o que exige minha atenção.
60. Como `GerenteRelacionamento`, quero abrir uma solicitação e ver o histórico do que aconteceu com ela,
    para que eu explique ao `Cliente` onde o pedido está.
61. Como `GerenteRelacionamento`, quero ver quem ou o que registrou cada fato, para que a automação tenha
    autor visível em vez de aparecer como origem vazia.
62. Como `GerenteRelacionamento`, quero continuar acessando o processo que originei mesmo se o `Cliente`
    sair da minha carteira, para que eu não perca o rastro de um trabalho que foi meu.
63. Como responsável pela governança, quero que esse acesso histórico **não** conceda acesso aos dados
    atuais do `Cliente`, para que permissão sobre o processo não vire permissão sobre a pessoa.

**Mensagens e operação**

64. Como `GerenteRelacionamento`, quero mensagens que digam exatamente o que a decisão significa, para que
    eu não invente explicações ao `Cliente`.
65. Como `GerenteRelacionamento`, quero uma mensagem única e acionável para falhas temporárias de
    integração, para que eu não precise entender a diferença entre timeout e indisponibilidade.
66. Como `GerenteRelacionamento`, quero que a indeterminação seja apresentada como acompanhamento e não
    como erro, para que eu não conclua que o sistema quebrou.
67. Como responsável pela operação, quero métricas de decisão e efetivação com cardinalidade controlada,
    para que o painel seja utilizável sem virar base de dados de negócio.
68. Como desenvolvedor, quero subir tudo por Docker Compose e forçar cenários de falha do Core, para que
    os caminhos de recuperação sejam demonstráveis em vez de teóricos.

## Implementation Decisions

### Deployables materializados por este slice

`app-gerente` (Angular 22), `bff-gerente`, `servidor-autorizacao`, `servico-carteira-clientes`,
`servico-credito`, `simulador-core-legado`, PostgreSQL com databases separados por contexto, Redis para a
sessão do BFF, e um executor one-shot de migrations. Docker Compose é o contrato oficial de execução
integrada (ADR-0013). `servico-risco`, `servico-movimentacoes`, `batch-movimentacoes`, `servico-auditoria`
e `servico-notificacoes` **não** são criados.

Cada serviço se organiza em domínio, aplicação e adapters, com a dependência apontando para dentro
(ADR-0020). Migrations são etapa explícita de deployment; nenhuma aplicação tem DDL em runtime (ADR-0014).

### Autenticação, sessão e delegação

`bff-gerente` é OAuth2 confidential client e conduz Authorization Code + PKCE + OIDC contra o
`servidor-autorizacao`. O browser recebe apenas cookie de sessão opaco; a sessão vive em Redis via Spring
Session. Proteção CSRF é requisito explícito. Tokens são audience-restricted: o BFF obtém por Token
Exchange um token para cada Resource Server, e `servico-credito`, ao chamar `servico-carteira-clientes`,
atua como OAuth client e troca o token que recebeu (ADR-0015).

Scopes são capacidades grossas — leitura de carteira, escrita de crédito — e nunca política de negócio. O
callback do `CoreLegado` chega ao `servico-credito` autenticado por `client_credentials`.

### Composição de tela

O `bff-gerente` compõe a tela de atendimento a partir de `servico-carteira-clientes` (identidade e
vínculo) e `servico-credito` (limite vigente). O resultado é modelo de apresentação, não agregado. O BFF
não implementa regra de crédito, não fala com o `simulador-core-legado` e não substitui a autorização de
recurso feita pelos serviços (ADR-0013).

### `servico-carteira-clientes`

Expõe a listagem paginada da carteira do gerente autenticado e uma operação de contexto de atendimento,
chaveada por conta, que devolve `clienteId` e o vínculo conta-cliente **somente** se o ator tiver direito
de atendimento **atual**; caso contrário responde `403`. `servico-credito` chama essa operação antes de
qualquer leitura no Core: sem direito, nenhuma consulta ao Core acontece.

Este contexto não é fachada financeira e não conhece `LimiteChequeEspecial` (ADR-0004).

**A verificação do direito de atendimento precede qualquer acesso ao Core, em toda consulta por conta.**
Vale para a tela inicial de atendimento e para a submissão: sem direito **atual**, a resposta é `403` e
**nenhuma** consulta ao `CoreLegado` acontece. O `bff-gerente` pode ocultar opções e barrar chamadas
obviamente indevidas, mas nunca é o enforcement point único — quem protege o recurso é o backend dono dele
(ADR-0007).

### Submissão da `SolicitacaoAumentoLimite`

Comando aceito de `app-gerente` via `bff-gerente`, contendo `contaId`, `limiteSolicitado`,
`limiteVigenteVisto` e a `ManifestacaoCliente` (`canalManifestacao` obrigatório entre `PRESENCIAL`,
`TELEFONE` e `CANAL_DIGITAL`; `observacao` opcional, texto livre, máximo 500 caracteres, com trim, e vazio
após trim equivalente a ausência).

`clienteId` **não** é aceito no comando — é derivado do vínculo autoritativo. A `OrigemSolicitacao` é
sempre `CLIENTE`, estabelecida pelo domínio.

Valores monetários trafegam e são persistidos em centavos, como inteiro. Ponto flutuante não é usado em
nenhuma camada (ADR-0005).

**Ordem de avaliação na submissão.** A sequência é normativa: separa o que é verificável localmente do
que depende do estado autoritativo, e é o que impede um `422` enganoso quando a tela do gerente ficou
desatualizada.

1. `Idempotency-Key` ausente → `400`.
2. Idempotência já conhecida: mesmo fingerprint com operação concluída → **replay**; chave em
   processamento → `409`; chave reutilizada com fingerprint incompatível → `422`.
3. Validações que **não dependem do Core**: `limiteSolicitado` positivo; `ManifestacaoCliente`
   estruturalmente válida; `observacao` dentro do limite; demais constraints puramente locais do
   comando. Falha → `422`, sem emitir chamada remota de negócio quando o defeito é detectável
   localmente.
4. Autorização de recurso em `CarteiraClientes` → `403` quando não há direito **atual**. Nenhuma
   chamada ao Core acontece.
5. Consulta ao `CoreLegado` pela ACL de `Credito`, obtendo os `DadosCreditoCore`:
   `LimiteChequeEspecialVigente`, `situacaoConta` e `ClassificacaoRiscoCreditoBase`, mais o instante da
   consulta e a procedência.
6. Optimistic check: `limiteVigenteVisto` diferente do `LimiteChequeEspecialVigente` lido agora →
   `409`, sem criar solicitação.
7. Só então a validação que depende do estado autoritativo: `limiteSolicitado` estritamente maior que o
   `LimiteChequeEspecialVigente`, e `422` quando não for. Isto é comando inválido, **não** decisão de
   crédito: não persiste `SolicitacaoAumentoLimite`, não gera `DecisaoCredito`, não gera Outbox, não
   gera histórico.
8. Unicidade: já existe solicitação não terminal para a conta → `409`.
9. Montagem do `ContextoDecisaoCredito` completo, e persistência pela fronteira TX1/TX2 descrita
   adiante.

O passo 6 precede o 7 deliberadamente. Gerente que viu 5.000, Core já em 6.000, pedido de 5.500: a
resposta é `409`, o estado visto está desatualizado — e nunca `422` "não aumenta o limite", que
descreveria uma comparação que o gerente não fez.

### Optimistic check (`limiteVigenteVisto`)

O valor enviado é o que o gerente **viu**, nunca autoritativo. Na submissão, `Credito` relê o vigente pela
ACL; divergência impede a criação. O front, ao receber `409`, recarrega o limite, atualiza
`limiteVigenteVisto` e **gera nova `Idempotency-Key`**, porque o payload canônico mudou.

### Idempotência da submissão

Chave `Idempotency-Key` (UUID) gerada pelo `app-gerente` e repassada intacta pelo `bff-gerente`, que não
gera, não regenera e não reinterpreta. Escopo de unicidade: `originadorId` + chave.

O servidor calcula um **fingerprint canônico** do comando recebido, cobrindo ao menos `contaId`,
`limiteSolicitado`, `limiteVigenteVisto`, `canalManifestacao` e `observacao` quando presente. Campos
derivados no servidor não entram no fingerprint.

- primeira requisição → processa; criação bem-sucedida responde `201`;
- mesma chave + mesmo fingerprint, operação concluída → **replay**: `200`, mesma `solicitacaoId`, mesmo
  resultado, sem nova solicitação, decisão, Outbox ou entrada de histórico;
- mesma chave + fingerprint diferente → `422`, operação original inalterada;
- mesma chave enquanto a primeira ainda está em processamento → `409`;
- chave ausente → `400`;
- falha antes da criação (`502`, `503`, `504`) → a chave **não** é consumida e o mesmo payload pode ser
  reenviado com a mesma chave quando a dependência voltar.

Uma `Idempotency-Key` identifica uma tentativa lógica de submissão com payload canônico fixo — não a vida
do formulário. Chave natural derivada de conta/valor/janela temporal é explicitamente rejeitada: uma nova
manifestação legítima do `Cliente` precisa poder gerar nova solicitação com os mesmos valores.

### Unicidade não terminal por `ContaCorrente`

Regra: no máximo uma `SolicitacaoAumentoLimite` não terminal por `ContaCorrente`. Não terminais:
`SOLICITADA`, `AGUARDANDO_EFETIVACAO`, `EFETIVACAO_INDETERMINADA`.

A regra **atravessa instâncias do agregado** e por isso não é imposta por uma delas isoladamente: a camada
de aplicação consulta por uma porta e recusa com `409` produzindo erro compreensível, e o PostgreSQL
garante correção sob concorrência real por unicidade restrita aos estados não terminais. Perder a corrida
no banco é traduzido para `409`, nunca devolvido como erro SQL cru.

### `ContextoDecisaoCredito` e `DadosCreditoCore`

Fotografia imutável contendo **somente** o que a decisão usou e o que permite reproduzi-la:
`limiteChequeEspecialVigente`, `situacaoConta`, `classificacaoRiscoCreditoBase`, `limiteSolicitado`,
`incrementoSolicitado` calculado e persistido, `versaoPoliticaCredito`, o instante da captura e a
procedência das informações externas.

Os três fatos externos vêm da mesma resposta do Core e são modelados como `DadosCreditoCore`, com
`consultadoEm` e identificação lógica da fonte — procedência registrada uma vez para o conjunto, não campo
a campo.

**Fora do contexto**: `clienteId`, `contaId`, evidência de autorização do gerente, nome, CPF, agência e
número formatado da conta. Identidade e autorização pertencem à `SolicitacaoAumentoLimite` e ao histórico
operacional; a pergunta "quem podia operar" não é insumo de "com quais fatos se decidiu".

Nenhuma solicitação é persistida com contexto incompleto (ADR-0006).

### `ClassificacaoRiscoCreditoBase`

Classificação simples já mantida pelo `CoreLegado` para a operação bancária corrente — `BAIXO`, `MEDIO`,
`ALTO` — lida por `Credito` pela sua própria ACL. É insumo interno da política e **não** é apresentada ao
`GerenteRelacionamento`. Não se confunde com `AvaliacaoRisco` nem `ResultadoAvaliacaoRisco`, que são o
processamento especializado do slice 2.

### `PoliticaCredito v1`

`versaoPoliticaCredito = "v1"`, determinística, avaliada em ordem, sobre o `ContextoDecisaoCredito`.
Constantes pertencem à versão da política e vivem em código/configuração da própria versão — não há tabela
parametrizável neste slice.

1. `situacaoConta` diferente de regular → `REJEITADA`, motivo `CONTA_NAO_ELEGIVEL`.
2. `classificacaoRiscoCreditoBase = ALTO` → `REJEITADA`, motivo `PERFIL_RISCO_INCOMPATIVEL`.
3. `classificacaoRiscoCreditoBase` em {`BAIXO`, `MEDIO`} **e** `limiteSolicitado <= R$ 10.000,00` **e**
   `incrementoSolicitado <= R$ 2.000,00` → `APROVADA`, motivo `DENTRO_DA_POLITICA_AUTOMATICA`.
4. qualquer caso restante → `REJEITADA`, motivo `FORA_DA_POLITICA_AUTOMATICA`.

A política v1 é **total**: o `MotorDecisaoCredito` sempre conclui `APROVADA` ou `REJEITADA`. Não existe
resultado inconclusivo, nem estado de espera por análise.

**A regra 4 não é juízo sobre o `Cliente`.** Ela afirma apenas que a versão vigente da política automática
não concede aquela solicitação. Ultrapassar a zona straight-through não é evidência de risco ruim, e o
motivo é deliberadamente distinto de `PERFIL_RISCO_INCOMPATIVEL` e de `CONTA_NAO_ELEGIVEL`. Quando o slice
2 existir, uma nova versão da política poderá encaminhar essa faixa para `AvaliacaoRisco` — evolução
explícita de política, não mudança retroativa do significado das decisões v1.

### `MotivoDecisaoCredito`

Código estável do domínio, nunca frase de interface. A API devolve estado, motivo, valores de negócio
necessários à apresentação e ações possíveis; o texto pt-BR pertence ao `app-gerente`.

### Máquina de estados

Estados, e apenas estes (ADR-0010 e sua emenda):

**Não terminais**: `SOLICITADA`, `AGUARDANDO_EFETIVACAO`, `EFETIVACAO_INDETERMINADA`.
**Terminais**: `EFETIVADA`, `REJEITADA`, `FALHA_EFETIVACAO`.

Transições válidas:

- criação → `SOLICITADA`
- `SOLICITADA` → `REJEITADA` (decisão automática recusa)
- `SOLICITADA` → `AGUARDANDO_EFETIVACAO` (decisão automática aprova)
- `AGUARDANDO_EFETIVACAO` → `EFETIVADA` (resultado autoritativo de sucesso)
- `AGUARDANDO_EFETIVACAO` → `FALHA_EFETIVACAO` (resultado autoritativo de falha definitiva)
- `AGUARDANDO_EFETIVACAO` → `EFETIVACAO_INDETERMINADA` (janela esgotada sem resultado autoritativo)
- `EFETIVACAO_INDETERMINADA` → `EFETIVADA`
- `EFETIVACAO_INDETERMINADA` → `FALHA_EFETIVACAO`

Estado terminal nunca é reescrito. Decidir novamente uma solicitação terminal é transição inválida.

`SOLICITADA` é o estado inicial e **durável**: existe entre TX1 e TX2 e é recuperável. Neste slice sua
duração é normalmente muito curta, porque a decisão automática ocorre em seguida na mesma requisição, mas
ele não é estado de passagem em memória — uma falha entre as duas unidades de trabalho deixa a solicitação
persistida nele. Conta como não terminal para a regra de unicidade, e ganha duração maior quando o slice 2
introduzir avaliação assíncrona.

### Fronteira transacional da submissão

**Nenhuma chamada remota acontece com transação PostgreSQL aberta.** A ordem é: autenticar e autorizar;
consultar `CarteiraClientes`; consultar o `CoreLegado`; montar e validar um `ContextoDecisaoCredito`
completo. Só então começa a persistência, em duas unidades de trabalho locais.

**TX1** — persiste a `SolicitacaoAumentoLimite` em `SOLICITADA`, junto com o `ContextoDecisaoCredito`
imutável e as informações de idempotência necessárias. Commit.

**TX2** — executa o `MotorDecisaoCredito` usando **exclusivamente** o `ContextoDecisaoCredito` já
persistido em TX1, e persiste a `DecisaoCredito`. Se
rejeitada, transiciona para `REJEITADA`. Se aprovada, transiciona para `AGUARDANDO_EFETIVACAO`, cria o
`EfetivacaoId` e grava no Outbox a intenção durável de efetivação. Commit.

A atomicidade que ADR-0009 exige é a de **TX2**: `DecisaoCredito` aprovada, `AGUARDANDO_EFETIVACAO`,
`EfetivacaoId` e linha de Outbox no mesmo commit. Rejeição não gera linha de Outbox nem qualquer chamada
de efetivação.

**Retomabilidade.** Falha entre TX1 e TX2 deixa a solicitação em `SOLICITADA` com contexto completo. A
operação de decisão é retomável sobre esse contexto já persistido, e **sem** consultar novamente os dados
vivos — reconsultar reescreveria a entrada da decisão, que é exatamente o que ADR-0006 impede.

### `EfetivacaoId`, instrução e `ProtocoloCore`

`EfetivacaoId` é identidade de negócio da tentativa lógica de efetivação, criada no registro durável da
intenção e estável por toda a vida dela, inclusive através de reenvios.

`EfetivacaoId` e `messageId` são identidades distintas e **ambas estáveis**: `EfetivacaoId` identifica a
operação de negócio perante o Core; `messageId` identifica a mensagem lógica registrada no Outbox. Um retry
da mesma mensagem preserva os dois. O que varia entre tentativas é metadado de entrega — contador de
tentativas, timestamps, último erro.

Instrução funcional mínima ao Core: `efetivacaoId`, identificação host da conta,
`limiteChequeEspecialVigenteEsperado`, `limiteSolicitado` e metadados de correlação.

O `limiteChequeEspecialVigenteEsperado` é o vigente congelado no `ContextoDecisaoCredito` e funciona como
**precondição**: se o Core já não estiver nesse estado, a alteração não é aplicada por cima e o Core
devolve resultado semanticamente equivalente a `LIMITE_VIGENTE_DIVERGENTE`.

O Core deduplica funcionalmente por `EfetivacaoId`: mesma instrução reenviada não aplica a alteração duas
vezes e devolve o mesmo `ProtocoloCore` quando este já existe; mesmo `efetivacaoId` com payload
incompatível é rejeitado explicitamente, e não tratado como operação nova. Essa deduplicação é
comportamento funcional do simulador, **não** capacidade do control plane.

Consulta de status é recuperável por `ProtocoloCore` quando conhecido e por `EfetivacaoId` quando o aceite
se perdeu. O contrato host-centric pode expor isso na forma que fizer sentido; o requisito é que ambos os
identificadores permitam recuperação.

### Taxonomia de resultados na ACL

Quatro classes, traduzidas pela ACL sem que nenhum `COD-RET` atravesse para dentro (ADR-0005):

- **aceite** — persiste `ProtocoloCore`; permanece `AGUARDANDO_EFETIVACAO`. Aceite não é conclusão.
- **transitório** — timeout, connection reset, `5xx` do host, código conhecido de indisponibilidade.
  Nenhuma transição de negócio; o dispatcher pode reenviar mantendo o mesmo `EfetivacaoId`.
- **definitivo** — retorno conhecido e autoritativo de que aquela efetivação não pode ser realizada: conta
  inexistente, conta bloqueada no instante da efetivação, instrução semanticamente inválida segundo o
  contrato, e `LIMITE_VIGENTE_DIVERGENTE`. Resultado: `FALHA_EFETIVACAO` com motivo traduzido.
- **indeterminado** — `COD-RET` desconhecido, payload malformado, campo obrigatório ausente, semântica que
  a ACL não conhece, impossibilidade prolongada de consultar. Não conclui nada: nem `EFETIVADA` nem
  `FALHA_EFETIVACAO`.

Na montagem do contexto, as mesmas patologias traduzem-se para a borda como: resposta inválida do Core →
`502`; indisponibilidade → `503`; timeout → `504`. Em todos, nada é persistido e a chave de idempotência
permanece reutilizável.

`LIMITE_VIGENTE_DIVERGENTE` é falha definitiva **daquela** efetivação. Não consultar o novo limite, não
recalcular incremento, não reaplicar política, não reenviar com o novo vigente — isso seria uma segunda
decisão de crédito sem `SolicitacaoAumentoLimite` nova. O caminho correto é nova solicitação.

### `FALHA_EFETIVACAO` exige evidência autoritativa

Entram em `FALHA_EFETIVACAO`: retorno definitivo do Core na instrução; callback definitivo de falha;
consulta de reconciliação respondendo falha definitiva; resposta explícita e autoritativa do Core de que
aquele `EfetivacaoId` nunca foi aceito, quando o contrato suportar semanticamente essa resposta.

**Não** entram: timeout, reset, código desconhecido, payload inválido, indisponibilidade prolongada, ou
simplesmente atingir o número máximo de tentativas. Perda de observabilidade não é falha de negócio, e
registrar isso como falha gravaria um fato possivelmente falso.

### Dispatcher

Entrega a instrução: tentativa inicial mais até 3 retries, com exponential backoff e jitter, valores
configuráveis. Defaults de demonstração aproximados: 1s, 2s, 4s, com jitter pequeno. Todas as tentativas
mantêm o mesmo `EfetivacaoId`; nunca se gera nova operação lógica para retry.

Esgotada a política curta de entrega, o dispatcher **para** de reenviar e não transforma isso em falha de
negócio. A recuperação passa ao reconciliador. Não existe loop infinito de retry.

### Callback

Endpoint máquina-a-máquina autenticado por `client_credentials`. Carrega `efetivacaoId`, `protocoloCore`,
resultado, código de resultado host, instante de processamento no Core quando disponível, e
`limiteEfetivado` quando o resultado for sucesso. O `correlationId` pode ser ecoado como metadado, mas
**não** é chave de negócio nem campo autoritativo vindo do Core: `Credito` recupera o seu pelo
`EfetivacaoId`.

A chave de correlação funcional é o `efetivacaoId`, e não o protocolo — no aceite perdido, o protocolo é
justamente o que não conhecemos.

- **sucesso incoerente** — o Core diz sucesso mas o `limiteEfetivado` é incompatível com o
  `limiteSolicitado` daquela efetivação. Não é resultado autoritativo utilizável: **não** transicionar para
  `EFETIVADA`, **não** sobrescrever o resultado esperado, registrar anomalia operacional observável e
  manter a operação recuperável. Uma reconciliação ou um callback posterior autoritativo e coerente ainda
  pode concluí-la. Se nada coerente chegar dentro da janela normal, a semântica é
  `EFETIVACAO_INDETERMINADA`, e **não** `FALHA_EFETIVACAO` — um contrato que não fecha é ignorância sobre o
  resultado, não evidência de que a efetivação falhou.
- **duplicado idêntico**, inclusive depois de a reconciliação já ter concluído com o mesmo resultado →
  `200`, sem transição, sem nova entrada de histórico, sem repetir efeitos.
- **antecipado** — callback chega antes de a resposta de aceite ser registrada: como o `EfetivacaoId` já
  foi persistido antes da chamada ao Core, o callback localiza a operação, pode aprender e persistir o
  `ProtocoloCore` e concluir a solicitação. O processamento posterior da resposta de aceite **não** regride
  o estado; protocolo igual é no-op idempotente.
- **sobre `EFETIVACAO_INDETERMINADA`** — válido, e conclui em `EFETIVADA` ou `FALHA_EFETIVACAO`.
- **contraditório** sobre estado terminal → estado **não** é reescrito automaticamente; responde `2xx` com
  indicação técnica de resultado conflitante registrado; gera log estruturado, métrica de anomalia e alerta
  operacional com todos os identificadores; **não** cria linha de histórico funcional fingindo transição. O
  `2xx` existe para não transformar inconsistência semântica em tempestade de redelivery. Tratamento manual
  dessa inconsistência é trabalho posterior.
- **`efetivacaoId` desconhecido** → `404`, com registro da ocorrência. Endpoint autenticado
  máquina-a-máquina; não há enumeração a proteger perante o próprio Core.
- **protocolo contraditório** — mesmo `EfetivacaoId` não adquire dois `ProtocoloCore` diferentes
  silenciosamente: não sobrescrever, registrar anomalia, responder `2xx`, preservar o estado conhecido.

### Reconciliação

Fronteira estrita: **o dispatcher entrega, o reconciliador pergunta**. O reconciliador nunca reenvia a
instrução — se reenviasse, haveria dois caminhos de entrega concorrendo pelo mesmo `EfetivacaoId`.

Consulta por `ProtocoloCore` quando conhecido, por `EfetivacaoId` quando não. Todos os resultados convergem
em `RegistrarResultadoEfetivacao`; nenhuma lógica de transição é duplicada no scheduler.

Agendamento pelo scheduling do próprio Spring, sem assumir instância única e sem eleição de líder: cada
execução reclama atomicamente conjuntos de pendentes no PostgreSQL.

Parâmetros operacionais, não SLA e não regra de domínio, com defaults de demonstração: elegível para
reconciliação após ~60s em `AGUARDANDO_EFETIVACAO`; varredura a cada ~30s; ~10 min como limite da janela
automática normal. Backoff configurável para a política de longa duração — não transformar 30s em polling
eterno.

Esgotada a janela sem resposta autoritativa: `AGUARDANDO_EFETIVACAO` → `EFETIVACAO_INDETERMINADA`, com
métrica, log estruturado e alerta operacional, mantendo a operação recuperável. Uma razão operacional do
tipo `RECUPERACAO_AUTOMATICA_ESGOTADA` pode ser registrada, e ela **não** afirma que o Core falhou em
efetivar.

Todos esses prazos são reduzíveis no profile `test` (centenas de milissegundos a poucos segundos). A
semântica é idêntica; muda só a escala temporal. Testes não usam `Thread.sleep` de minutos.

### `RegistrarResultadoEfetivacao`

Caso de uso único e idempotente de conclusão, usado por callback e reconciliação. É a única porta de saída
de `AGUARDANDO_EFETIVACAO` e de `EFETIVACAO_INDETERMINADA`. Recebê-lo duas vezes não duplica histórico, não
provoca nova alteração e não produz transição inválida.

### Histórico funcional

Trilha append-only em `credito_db`, por solicitação. É histórico funcional do processo de crédito — **não**
é Event Sourcing, não é Kafka, não é `servico-auditoria` e não é fonte de reconstrução do agregado. O estado
atual continua persistido pelo modelo normal; a trilha explica como se chegou até ele.

Fatos registrados no slice 1: solicitação registrada; decisão automática registrada; efetivação solicitada;
instrução aceita pelo Core; resultado de efetivação registrado; entrada em `EFETIVACAO_INDETERMINADA`.

Não geram entrada: callback duplicado idêntico; replay idempotente de submissão; execução de scheduler sem
mudança; retry técnico sem fato novo.

Cada entrada tem identidade estável própria ou derivada do fato causador, suficiente para deduplicar sob
redelivery — a restrição é que o mesmo fato lógico não produza duas entradas; a forma concreta pertence ao
ticket.

Cada entrada registra `AtorOperacao`: `AtorHumano(originadorId)` na submissão;
`AtorSistema(MOTOR_DECISAO_CREDITO)` na decisão; `AtorSistema(CORE_LEGADO)` no resultado recebido por
callback; `AtorSistema(RECONCILIACAO_EFETIVACAO)` quando o resultado foi descoberto por reconciliação,
podendo registrar adicionalmente que a fonte autoritativa foi o `CORE_LEGADO`. Quem informou o fato e qual
mecanismo interno o observou são coisas distintas.

### `MinhasSolicitacoesAumentoLimite`

Listagem paginada, ordenada da mais recente para a mais antiga, contendo **somente** solicitações cujo
`originadorId` é o ator autenticado. Cada item traz o suficiente para localizar e compreender o processo:
`solicitacaoId`, `contaId`, instante do registro, `limiteSolicitado`, status atual e resultado da decisão
quando existir.

A listagem **não** é obrigatoriamente enriquecida com dados atuais de `CarteiraClientes` — justamente porque
o originador mantém acesso ao processo mesmo quando o `Cliente` deixa sua carteira. Selecionar um item abre
detalhes e histórico.

### Acesso e autorização de recurso

O ator precisa estar autenticado e ter a capacidade de `GerenteRelacionamento`; ser o originador não
substitui autenticação nem papel.

O originador acessa a solicitação que originou e seu histórico mesmo que o `Cliente` tenha deixado sua
carteira. Isso é autorização sobre o **processo de Crédito**, e não sobre o `Cliente`: não concede abrir o
perfil atual, consultar a `ContaCorrente` ou o limite atual, nem originar nova solicitação — tudo isso
continua exigindo direito de atendimento **atual** em `CarteiraClientes`. A tela de histórico renderiza a
partir dos dados persistidos do próprio processo e não usa a permissão histórica para compor dados atuais.

No slice 1 o acesso histórico é concedido **somente ao originador** (ADR-0007 e sua emenda).

### `correlationId`

Gerado por `servico-credito` quando uma `SolicitacaoAumentoLimite` é efetivamente criada, e persistido junto
ao processo. Propagado por `DecisaoCredito`, `EfetivacaoId`, Outbox, callback, reconciliação e histórico.
Replay idempotente recupera o `correlationId` existente. Tentativas que falham antes da criação podem ter
traces técnicos distintos e não precisam de `correlationId` de negócio persistido.

Não é `traceId` (execução técnica, ADR-0017) e não é `Idempotency-Key` (tentativa de submissão). O
`bff-gerente` participa da observabilidade técnica por W3C Trace Context, mas não é dono do processo de
Crédito.

### Contrato do `simulador-core-legado`

Semântica host-centric sobre HTTP (ADR-0005): nomes abreviados, códigos numéricos, datas `yyyyMMdd`, dinheiro
em centavos com zero-padding, campos opcionais em branco, códigos de retorno próprios, e `200` podendo
carregar erro de negócio.

Capacidades funcionais exigidas por este slice: consulta dos dados de crédito da conta (limite vigente,
situação da conta, classificação de risco base); recepção da instrução de efetivação com deduplicação por
`EfetivacaoId` e devolução de `ProtocoloCore`; consulta de status por protocolo e por `EfetivacaoId`; e
disparo do callback.

O contrato precisa ter casos **semanticamente distinguíveis** para: sucesso/aceite; indisponibilidade
transitória; conta não encontrada; falha definitiva; `LIMITE_VIGENTE_DIVERGENTE`; código desconhecido;
payload inválido. Os números concretos dos `COD-RET` são detalhe fictício do simulador e pertencem ao ticket.

**Control plane separado**, ativo apenas em profiles como `local`, `demo` e `test`, capaz de configurar:
sucesso, `COD-RET` de erro, timeout, atraso, aceite perdido, callback atrasado, duplicado ou suprimido, falha
definitiva, indisponibilidade e resposta malformada. É deliberadamente separado do contrato funcional e não
faz parte da interface do Core (ADR-0018).

### Persistência

`servico-credito` persiste em `credito_db`, nome já estabelecido por ADR-0014.
`servico-carteira-clientes` possui **armazenamento privado próprio**, com credencial e migrations
separadas; o nome físico desse database pertence ao ticket que materializar a persistência e não é
fixado aqui. Nenhum outro bounded context acessa diretamente o armazenamento de `CarteiraClientes`
(ADR-0011, ADR-0014). Requisitos de comportamento, com forma física deixada ao ticket:

- unicidade de solicitação não terminal por `ContaCorrente`, imposta pelo banco;
- registro de idempotência por (`originadorId`, `Idempotency-Key`) com fingerprint e `solicitacaoId`
  resultante, e estado suficiente para distinguir em processamento, concluída e falha pré-criação;
- Outbox único por serviço, com destino como metadado, gravado na mesma transação da decisão (TX2), com
  `messageId` estável por mensagem lógica e o metadado de entrega — tentativas, timestamps — separado dele;
- `ProtocoloCore` e `EfetivacaoId` persistidos e consultáveis;
- trilha de histórico append-only com deduplicação por identidade de fato;
- claim transacional de pendentes para a reconciliação, seguro sob execução concorrente.

### Contratos e erros

OpenAPI versionado é o artefato canônico das interfaces REST (ADR-0019), cobrindo BFF, Resource Servers e o
contrato host-centric do simulador.

Resumo dos códigos na submissão: `201` criada; `200` replay; `400` `Idempotency-Key` ausente; `403` sem
direito de atendimento; `409` limite visto desatualizado, solicitação não terminal já existente, ou mesma
chave em processamento; `422` comando inválido ou chave reutilizada com fingerprint diferente; `502` resposta
inválida do Core; `503` Core indisponível; `504` timeout.

Callback: `200` processado, duplicado ou conflito registrado; `404` `efetivacaoId` desconhecido; erro de
autenticação conforme o padrão do Resource Server.

### Apresentação

A API devolve estado, motivo, valores e ações possíveis; textos pt-BR vivem no `app-gerente`.

- `PERFIL_RISCO_INCOMPATIVEL` informa o motivo, **nunca** a `ClassificacaoRiscoCreditoBase`.
- `CONTA_NAO_ELEGIVEL` informa o motivo, nunca o status host bruto.
- `FORA_DA_POLITICA_AUTOMATICA` preserva a semântica exata: a solicitação não se enquadra na política de
  concessão automática vigente. Não afirmar risco elevado, problema cadastral, inelegibilidade permanente
  nem impossibilidade geral de concessão.
- Aprovada: exibir simultaneamente o `LimiteChequeEspecialVigente` ainda confirmado pelo Core, o
  `limiteSolicitado` marcado como pendente de efetivação, e o estado `AGUARDANDO_EFETIVACAO`. Nunca
  substituir o vigente pelo solicitado antes da confirmação autoritativa.
- `EFETIVACAO_INDETERMINADA`: apresentação própria, não "erro". Comunicar que o resultado ainda não pôde ser
  confirmado, que a solicitação segue em acompanhamento pelo sistema, e que enquanto isso uma nova
  solicitação para aquela conta não pode ser iniciada. Não afirmar que efetivou nem que falhou.
- `502`/`503`/`504`: mensagem única de negócio ("não foi possível concluir a operação agora, tente
  novamente"). A distinção permanece em protocolo, métricas e diagnóstico.
- `422` de chave reutilizada: erro técnico genérico, sem culpar o gerente, com métrica de anomalia. Se chegar
  à UI, é bug do frontend.

### Observabilidade

Micrometer Observation/Tracing, OTLP, Collector (ADR-0017). Métricas de negócio com cardinalidade controlada
— resultado, origem e `versaoPoliticaCredito` são aceitáveis; `clienteId`, `contaId`, `solicitacaoId`,
`protocoloCore` e `correlationId` não entram em série temporal. Tempo de permanência em
`AGUARDANDO_EFETIVACAO`, entradas em `EFETIVACAO_INDETERMINADA` e anomalias de callback são métricas deste
slice. Logs estruturados em JSON, sem CPF completo, número completo de conta, tokens ou payloads completos do
Core. O domínio não chama API de observabilidade.

## Testing Decisions

Um bom teste aqui afirma **comportamento externo observável**: resultado na fronteira do sistema e/ou efeito
durável verificável — incluindo explicitamente a **ausência** de efeito quando isso faz parte da regra. Nenhum
critério é escrito em termos de método interno chamado, classe utilizada, número de mocks ou implementação
privada.

Regra de distribuição: **cada responsabilidade é provada no seam de menor custo capaz de falsificá-la. Seams
superiores confirmam a integração entre responsabilidades, mas não repetem exaustivamente as mesmas
combinações.** `PoliticaCredito` tem todas as regras provadas em S1; S2 prova que a aplicação invoca a política
e reage ao resultado; S6 prova que uma entrada HTTP produz o contrato esperado; S7 prova que a topologia fecha.

Não existe prior art: o repositório é greenfield e todos os seams são novos. Não há
`disable-security-for-tests` (ADR-0018).

### S1 — Domínio de `Credito`, JUnit puro, sem Spring

`PoliticaCredito v1` nas quatro faixas; regras e transições de `SolicitacaoAumentoLimite`, incluindo transição
inválida e não reescrita de estado terminal; `EFETIVACAO_INDETERMINADA` e suas duas saídas; cálculo do
`IncrementoSolicitado`; validações puras; imutabilidade do `ContextoDecisaoCredito`.

### S2 — Aplicação de `Credito`, sem Spring, ports como fakes

`RegistrarSolicitacaoAumentoLimite`; orquestração da montagem do contexto; comportamento quando as dependências
não fornecem contexto completo; `RegistrarResultadoEfetivacao`; convergência callback/reconciliação;
idempotência da orquestração; separação dispatcher/reconciliador; coordenação entre domínio e ports. Fake
comportamental pequeno é preferível a árvores extensas de mocks.

### S3 — PostgreSQL real via Testcontainers

Unicidade de solicitação não terminal por `ContaCorrente` sob corrida concorrente real; registro de
`Idempotency-Key` e fingerprint; atomicidade decisão/status/Outbox; idempotência persistente; claim transacional
da reconciliação; deduplicação de histórico; migrations e constraints relevantes. **H2 não substitui esses
testes.**

### S4 — ACL do Core contra mock HTTP server

Mesmo HTTP client e adapter de produção. `200` com `COD-RET` de erro; campos em branco; zero-padding inesperado;
data inválida; código desconhecido; resposta malformada; timeout; reset e indisponibilidade; mapeamento para
`502`, `503`, `504`; `LIMITE_VIGENTE_DIVERGENTE`; conversão entre payload legado e tipos internos.

### S5 — Contract/integration smoke contra o `simulador-core-legado` real

Conjunto pequeno; não duplica a matriz patológica de S4. Detecta deriva entre o adapter de `Credito` e o
simulador, e prova os comportamentos de protocolo que exigem estado do Core: deduplicação por `EfetivacaoId`;
recuperação do mesmo `ProtocoloCore`; consulta por `EfetivacaoId`; precondição do limite vigente esperado.

### S6 — Spring integration e segurança

Responsabilidade estrita: routing HTTP, serialização, validação de request, status codes, JWT, issuer/audience,
scopes, roles, autorização de recurso, Token Exchange, autenticação máquina-a-máquina do callback, sessão e
segurança do BFF, cookie, CSRF, wiring. **Não** reexamina a `PoliticaCredito` nem a máquina de estados.

### S7 — Playwright, exatamente quatro jornadas

Autenticação real, Compose integrado, sem bypass de segurança:

1. straight-through approval até `EFETIVADA`;
2. rejeição automática;
3. callback perdido recuperado pela reconciliação;
4. `EFETIVACAO_INDETERMINADA` seguida de conclusão tardia.

### S8 — ArchUnit

Guardrail arquitetural independente: domínio sem Spring e sem adapters; aplicação sem dependência de adapters;
adapters apontando para dentro; ausência de JPA, Kafka e Rabbit dentro do domínio; ausência de dependência Java
direta indevida entre bounded contexts. ArchUnit não é prova de qualidade do modelo de domínio.

### Afordâncias de teste

O control plane do simulador habilita S5 e S7. Os prazos de reconciliação são parametrizados por profile e
colapsados em `test`.

### Critérios de aceitação

Cada critério indica onde a propriedade é melhor falsificada; a lista **não** obriga um teste por célula.

- **AC1 — Aprovação straight-through** (S1+S2+S3+S7): conta regular, classificação permitida e valores na faixa
  automática → solicitação criada; `DecisaoCredito = APROVADA`; `versaoPoliticaCredito = v1`; estado
  `AGUARDANDO_EFETIVACAO`; uma única intenção durável de efetivação; após confirmação autoritativa, `EFETIVADA`;
  consulta posterior ao Core apresenta o novo vigente; histórico contém cada fato uma única vez.
- **AC2 — Conta não elegível** (S1+S2): solicitação persistida; `REJEITADA` com `CONTA_NAO_ELEGIVEL`; nenhuma
  intenção de efetivação; nenhuma chamada de efetivação ao Core.
- **AC3 — Perfil de risco incompatível** (S1+S2): solicitação persistida; `REJEITADA` com
  `PERFIL_RISCO_INCOMPATIVEL`; nenhuma efetivação iniciada. A API não expõe a classificação bruta.
- **AC4 — Fora da política automática** (S1+S2): solicitação persistida; `REJEITADA` com
  `FORA_DA_POLITICA_AUTOMATICA`, motivo distinto dos dois anteriores; nenhuma efetivação iniciada.
- **AC5 — Não aumenta o limite** (S1+S6): com `limiteVigenteVisto` **coerente** com o vigente lido no
  Core, um `limiteSolicitado` que não é estritamente maior devolve `422`, e não cria solicitação,
  decisão, Outbox ou histórico. `limiteSolicitado` não positivo também é `422`, detectado no passo de
  validação local, antes de qualquer chamada ao Core.
- **AC6 — Limite visto desatualizado** (S2+S6+teste de frontend): `409` e nenhuma solicitação criada. O
  caso decisivo, que fixa a precedência do passo 6 sobre o 7: visto 5.000, Core em 6.000, pedido de
  5.500 → **`409`, nunca `422`**. O front, diante do `409`, atualiza o limite e gera nova
  `Idempotency-Key`, e a nova tentativa é processada normalmente. **Não** exige Playwright.
- **AC7 — Falhas na montagem** (S4+S2+S6): resposta inválida → `502`; indisponibilidade → `503`; timeout →
  `504`. Em todos: nada persistido parcialmente, nenhuma decisão, nenhuma efetivação; retry com mesmo payload e
  mesma chave prossegue após a dependência voltar.
- **AC8 — Replay idempotente** (S2+S3+S6): `200`, mesma `solicitacaoId`, nenhuma nova solicitação, decisão,
  Outbox ou entrada de histórico.
- **AC9 — Reutilização inválida da chave** (S2+S3+S6): `422`; operação original inalterada; nenhuma nova
  solicitação.
- **AC10 — Requisições simultâneas para a mesma conta** (S3): apenas uma cria solicitação não terminal; a outra
  recebe `409`; nunca existem duas não terminais para a mesma conta; uma existente em
  `EFETIVACAO_INDETERMINADA` continua bloqueando. Concorrência real contra PostgreSQL, não mock de repositório.
- **AC11 — Aceite perdido** (S2+S4+S5): reenvio usa o mesmo `EfetivacaoId`; Core não aplica de novo; mesmo
  `ProtocoloCore` recuperado; existe uma única operação lógica de efetivação.
- **AC12 — Callback perdido** (S2+S5+S7): reconciliação consulta por protocolo ou `EfetivacaoId`, converge por
  `RegistrarResultadoEfetivacao`, termina `EFETIVADA`, histórico atribui a conclusão ao mecanismo de
  reconciliação, nenhuma segunda efetivação é enviada.
- **AC13 — Callback duplicado** (S2+S3+S6): `200`; estado inalterado; número de entradas funcionais de histórico
  inalterado; nenhum novo efeito.
- **AC14 — Callback antecipado** (S2+S3): callback localiza a operação por `EfetivacaoId`; conclui; aprende o
  `ProtocoloCore`; o processamento posterior do aceite não regride o estado; mesmo protocolo é idempotente.
- **AC15 — Limite vigente divergente** (S1+S2+S4+S5): resultado funcional `LIMITE_VIGENTE_DIVERGENTE`;
  `FALHA_EFETIVACAO`; nenhuma segunda operação lógica; nenhuma nova `DecisaoCredito`; nenhum recálculo
  automático; nova tentativa exige nova `SolicitacaoAumentoLimite`. Provado por efeitos duráveis e interações
  observáveis com o Core, não por chamada de método interno.
- **AC16 — Efetivação indeterminada** (S1+S2+S7): esgotada a janela sem resultado autoritativo →
  `EFETIVACAO_INDETERMINADA`, **não** `FALHA_EFETIVACAO`; nenhuma nova solicitação para a conta é aceita;
  callback autoritativo posterior conclui em `EFETIVADA`; equivalente de domínio/aplicação cobre a conclusão
  tardia em falha autoritativa.
- **AC17 — Callback contraditório após conclusão** (S2+S3+S6): `2xx`; estado terminal não reescrito; histórico
  não inventa transição; nenhuma nova efetivação; anomalia operacional observável registrada.
- **AC18 — Decisão retomável entre as unidades de trabalho** (S2+S3): interrompida a operação após o
  commit de TX1 e antes de TX2, a solicitação permanece persistida em `SOLICITADA` com
  `ContextoDecisaoCredito` completo; a retomada produz a decisão a partir desse contexto congelado, sem
  nova consulta a `CarteiraClientes` ou ao `CoreLegado`, e o resultado é idêntico ao que teria sido
  produzido sem a interrupção. Nenhuma chamada remota ocorre com transação aberta.
- **AC19 — Login real e tokens fora do browser** (S6+S7): a autenticação acontece contra o
  `servidor-autorizacao` por Authorization Code + PKCE + OIDC; ao final, o browser possui apenas o cookie
  de sessão do `bff-gerente`, com atributos de segurança esperados; nem access token nem refresh token
  aparecem em resposta, corpo, storage do browser ou qualquer superfície acessível ao Angular.
- **AC20 — Sessão, logout e CSRF** (S6): a sessão sobrevive ao restart da instância do `bff-gerente` que a
  originou, porque vive em Redis; o logout a invalida, e uma requisição subsequente com o mesmo cookie é
  recusada; uma requisição de escrita sem o token CSRF esperado é recusada, e a mesma requisição com o
  token é aceita.
- **AC21 — Token Exchange e restrição de audience** (S6): o `bff-gerente` chama cada Resource Server com
  token cuja `aud` é aquele destino; `servico-credito`, ao continuar a operação em nome do usuário contra
  `servico-carteira-clientes`, apresenta token obtido por Token Exchange com a `aud` correta; um token
  válido emitido para outro Resource Server é **recusado** pela validação de `aud`; e os scopes exigidos
  permanecem capacidades grossas, sem nenhum scope que codifique política de crédito. Estes testes não
  reexaminam regra de crédito.
- **AC22 — Carteira do gerente** (S6): o gerente autenticado vê somente os `Cliente`s da sua
  `CarteiraClientes`; a listagem é paginada; selecionar um `Cliente` devolve suas `ContaCorrente`s.
- **AC23 — Autorização de recurso precede o Core** (S2+S6): sem direito de atendimento **atual**, tanto a
  consulta do `LimiteChequeEspecialVigente` por conta quanto a submissão respondem `403`, e **nenhuma**
  chamada ao `CoreLegado` é emitida. A recusa é produzida pelo backend dono do recurso, e continua
  valendo quando a requisição chega sem passar pelas restrições de navegação do `app-gerente` — o
  `bff-gerente` não é o enforcement point único.
- **AC24 — `MinhasSolicitacoesAumentoLimite`** (S2+S3+S6): a listagem devolve somente solicitações cujo
  `originadorId` é o ator autenticado, nunca as de outro gerente; é paginada; vem ordenada da mais
  recente para a mais antiga; e cada item permite abrir detalhes e o histórico daquela solicitação.
- **AC25 — Duas autorizações distintas** (S6): dado que um gerente originou uma solicitação e que o
  `Cliente` depois deixou sua carteira, ele **continua** conseguindo consultar aquela solicitação e seu
  histórico; e **não** consegue, por causa dessa permissão histórica, abrir os dados atuais do `Cliente`,
  consultar a `ContaCorrente` atual, consultar o limite atual, nem originar nova solicitação. As duas
  autorizações são exercitadas separadamente: processo histórico em `Credito` contra recurso atual em
  `CarteiraClientes`.
- **AC26 — Callback de sucesso incoerente** (S2+S4): callback declarando sucesso com `limiteEfetivado`
  incompatível com o `limiteSolicitado` daquela efetivação **não** transiciona para `EFETIVADA` e **não**
  sobrescreve o resultado esperado; registra anomalia operacional observável; a operação permanece
  recuperável, e um resultado autoritativo coerente posterior — por callback ou por reconciliação — ainda
  a conclui. Esgotada a janela normal sem informação coerente, o estado é `EFETIVACAO_INDETERMINADA`, e
  **não** `FALHA_EFETIVACAO`.
- **AC27 — Manifestação e origem** (S2+S3+S6): `canalManifestacao` é obrigatório e restrito a
  `PRESENCIAL`, `TELEFONE` e `CANAL_DIGITAL`; `observacao` é opcional, sofre trim, é recusada acima de
  500 caracteres, e vazia após trim equivale a ausência; a `OrigemSolicitacao` persistida é `CLIENTE`
  independentemente do que o payload contenha, e um comando que tente enviar origem ou `clienteId` não
  altera o que é gravado.
- **AC28 — Retry do dispatcher** (S2+S4): diante de falha transitória na entrega, a instrução é reenviada
  com o **mesmo** `EfetivacaoId` até o limite configurado, com backoff; esgotado o limite, o dispatcher
  para de reenviar, a solicitação permanece em `AGUARDANDO_EFETIVACAO` e **não** transiciona para
  `FALHA_EFETIVACAO`; nunca se cria uma segunda operação lógica de efetivação.
- **AC29 — O vigente exibido nunca é o solicitado** (S6+S7): enquanto a solicitação está em
  `AGUARDANDO_EFETIVACAO` ou `EFETIVACAO_INDETERMINADA`, a resposta da API e a tela apresentam o
  `LimiteChequeEspecialVigente` confirmado pelo Core e o `limiteSolicitado` marcado como pendente de
  efetivação; o valor solicitado só passa a figurar como vigente depois de `EFETIVADA` e de leitura
  autoritativa do Core.
- **AC30 — Composição é do BFF** (S6): o modelo de apresentação da tela de atendimento é montado pelo
  `bff-gerente` a partir de `servico-carteira-clientes` e `servico-credito`; o BFF não emite nenhuma
  chamada ao `simulador-core-legado`; `servico-carteira-clientes` não expõe nem conhece
  `LimiteChequeEspecial`; e `servico-credito` não devolve dados cadastrais do `Cliente` que pertencem a
  `CarteiraClientes`. A ausência de dependência Java direta entre os contextos é coberta por S8.
- **AC31 — Nova manifestação legítima com os mesmos valores** (S2+S3+S6): dada uma solicitação anterior
  em estado terminal para a mesma conta, uma nova submissão com **os mesmos valores** e uma
  `Idempotency-Key` nova cria uma **nova** `SolicitacaoAumentoLimite`. É a asserção que justifica ter
  rejeitado chave natural derivada de conta, valor e janela temporal.
- **AC32 — Procedência congelada no contexto** (S1+S3): o `ContextoDecisaoCredito` persistido carrega a
  procedência lógica dos `DadosCreditoCore` e o instante da consulta; alterar o Core depois **não**
  altera o que a decisão histórica apresenta, e reler a solicitação devolve os mesmos valores e a mesma
  `versaoPoliticaCredito`.
- **AC33 — Fronteira entre identidade e fatos de decisão** (S1+S3+S8): `clienteId`, `contaId` e
  `originadorId` estão na `SolicitacaoAumentoLimite`, e não no `ContextoDecisaoCredito`; a evidência de
  autorização de atendimento não integra o contexto; e a `PoliticaCredito v1` é reproduzível a partir
  **exclusivamente** dos fatos congelados — reaplicá-la sobre o contexto persistido devolve a mesma
  `DecisaoCredito` e o mesmo `MotivoDecisaoCredito`, sem acesso a nada fora dele.
- **AC34 — Reconciliação sob concorrência** (S3): com duas instâncias do reconciliador executando
  simultaneamente contra o mesmo conjunto de efetivações pendentes, cada efetivação é reclamada por uma
  única claim lógica por ciclo; nenhuma é processada duas vezes no mesmo ciclo e nenhuma fica órfã.
  Contra PostgreSQL real, sem eleição de líder.
- **AC35 — Indeterminação é observável** (S6): ao entrar em `EFETIVACAO_INDETERMINADA`, a métrica
  operacional correspondente é incrementada, o log estruturado é emitido com os identificadores
  necessários à investigação, e o mecanismo de alerta configurado recebe o sinal. Nenhum desses
  artefatos afirma que houve falha de efetivação. Não se prova isso em JUnit de domínio — o domínio não
  chama API de observabilidade (ADR-0017).
- **AC36 — Cardinalidade das métricas** (S6): os meters registrados por este slice usam apenas labels de
  baixa cardinalidade — resultado, origem, `versaoPoliticaCredito` — e **nenhum** meter carrega
  `clienteId`, `contaId`, `solicitacaoId`, `protocoloCore` ou `correlationId` como label. Verificável
  inspecionando o registry, sem Prometheus externo.
- **AC37 — Mensagens apresentadas ao gerente** (teste de frontend + S6): `FORA_DA_POLITICA_AUTOMATICA`
  renderiza a semântica exata — não se enquadra na política de concessão automática vigente — e o texto
  não afirma risco elevado, problema cadastral nem inelegibilidade permanente; `502`, `503` e `504`
  renderizam uma **única** mensagem de indisponibilidade com ação de repetir, sem expor qual das três
  ocorreu; `EFETIVACAO_INDETERMINADA` renderiza como acompanhamento, com o aviso de que nova
  solicitação para aquela conta não pode ser iniciada, e nunca como erro. A API devolve estado, motivo
  e valores — nenhum texto de interface vem do backend.

## Out of Scope

`AvaliacaoRisco` e `servico-risco`. RabbitMQ e Kafka — o Outbox existe, mas seu destino neste slice é a
instrução REST ao Core; `DecisaoCreditoRegistrada` e `LimiteEfetivado` como fatos publicados ficam para o
slice 6. `AnalistaCredito`, `ParecerCredito`, `SupervisorCredito`, decisão humana, `PerfilAlcadaAprovacao`,
`AtribuicaoAlcada` e alçadas humanas em execução — maker-checker não é tensionado neste slice porque a decisão é
do `AtorSistema` `MOTOR_DECISAO_CREDITO`. `servico-movimentacoes`, `batch-movimentacoes`, extrato, indicadores
financeiros no `ContextoDecisaoCredito`, Spring Batch, staging e processamento de arquivo legado.
`servico-auditoria` e `servico-notificacoes`. UI administrativa, busca avançada e relatórios gerenciais.
Listagem de solicitações por `Cliente`, por `ContaCorrente` ou por carteira; filtros complexos; visão de
supervisor e de auditor. Estados `EM_AVALIACAO_RISCO`, `AGUARDANDO_PARECER` e `AGUARDANDO_APROVACAO`. Tratamento
humano de callback contraditório. Kubernetes, Helm e manifests.

## Further Notes

**Origem.** Esta spec consolida quatro rodadas de grilling encerradas com "não há ambiguidade material
bloqueando a specification", cujo resultado documental está em `0fa7946` — `CONTEXT.md` mais emendas datadas em
ADR-0007, ADR-0009, ADR-0010 e ADR-0018.

**ADRs vinculantes.** 0001 (pt-BR), 0002 (Core é system of record), 0004 (ACL por contexto), 0005 (contrato
host-centric), 0006 (fotografia imutável), 0007 e emenda (maker-checker, autorização de recurso, acesso
histórico), 0009 e emenda (efetivação assíncrona, `EfetivacaoId`, indeterminação), 0010 e emenda (só o que a
spec exige; estados do slice), 0012 (baseline de stack), 0013 (deployables), 0014 (dados por contexto, migração
no deployment), 0015 (sessão no BFF, Token Exchange), 0017 (observabilidade), 0018 e emenda (fronteiras de
teste, jornadas E2E), 0019 (OpenAPI canônico), 0020 (hexagonal pragmático).

**Três inversões que o grilling produziu**, e que a implementação não deve desfazer por conveniência:

1. esgotar a recuperação **não** é falha — daí `EFETIVACAO_INDETERMINADA`;
2. a unicidade por conta **não** é invariante interna de uma instância do agregado;
3. ultrapassar o teto da aprovação automática **não** é evidência de risco ruim.

**Deixado aos tickets.** Nomes de endpoints e paths; schemas concretos do OpenAPI; nomes de tabelas, DDL,
índices e formato das migrations; nomes de classes Java e packages; nomes host-centric exatos dos campos do
simulador e os números concretos dos `COD-RET`; número exato de tentativas de entrega além do default proposto;
forma física do registro de histórico e do registro de idempotência.

**Materialização de glossário.** Quando `Credito` e `CarteiraClientes` forem materializados em código, seu
vocabulário migra do `CONTEXT.md` raiz para `src/<contexto>/CONTEXT.md`, conforme `CONTEXT-MAP.md` e
`docs/agents/domain.md`. Esta spec é o gatilho dessa migração; ela não deve acontecer antecipadamente nem criar
arquivos vazios.

## Log

### 2026-09-02 — franklin.azeredo

Specification consolidada a partir de quatro rodadas de grilling, encerradas com "não há ambiguidade
material bloqueando a specification". A base documentária está em `0fa7946`: `CONTEXT.md` mais emendas
datadas em ADR-0007, ADR-0009, ADR-0010 e ADR-0018.

Duas revisões posteriores: a fronteira transacional passou de uma única transação para TX1/TX2 sem
chamada remota aberta, `messageId` passou a ser estável em retry, o callback de sucesso incoerente ganhou
desfecho, e os critérios de aceitação foram de 17 para 29 cobrindo autenticação, Token Exchange,
carteira, autorização de recurso e acompanhamento.

Este documento nasceu como GitHub issue #1 e migrou para o repositório quando o tracker passou a ser
markdown versionado. A issue foi removida; não há cópia viva fora daqui.
