# Credito

Glossário deste bounded context, materializado em código pelo ticket #0002 (slice 1) com o
deployable `fk-servico-credito`. Vocabulário transversal — **Atores** — permanece no `CONTEXT.md`
raiz por ser compartilhado entre contextos (`docs/agents/domain.md`); o mapa geral está em
`CONTEXT-MAP.md`.

A linguagem ubíqua é **pt-BR** (ADR-0001). Identificadores não levam acento nem cedilha; a prosa
deste arquivo leva.

**Core domain.** Responsável pelo processo de aumento de limite de ponta a ponta: solicitação,
política, decisão, alçada e efetivação. É o **dono semântico do LimiteChequeEspecial** e consulta
no CoreLegado, pela sua própria ACL, as informações de crédito de que precisa (ADR-0004).

O glossário abaixo é o vocabulário **completo** do contexto, e não apenas o que já existe em
código: ele descreve o domínio que a spec do slice 1 exercita inteiro. #0002 materializou a fatia
da leitura — `LimiteChequeEspecialVigente`, `DadosCreditoCore`, `ClassificacaoRiscoCreditoBase` e a
`situacaoConta` —, e `fk-servico-credito` nasceu deliberadamente **sem persistência**, porque ainda
não havia estado durável de Credito (ADR-0010, ADR-0014). #0003 materializou o primeiro
comportamento com estado durável — `credito_db`, a submissão da `SolicitacaoAumentoLimite`, o
congelamento do `ContextoDecisaoCredito` e a decisão automática da `PoliticaCredito v1` —, deixando
a efetivação em si para #0004+: #0004 materializou o dispatcher (entrega da instrução, taxonomia
de resultados da ACL, `RegistrarResultadoEfetivacao` como caso de uso único de conclusão), #0005 o
callback de confirmação (a variante de sucesso do mesmo caso de uso, e a convergência entre
callback e dispatcher sob concorrência real), e a reconciliação fica para #0006.


**LimiteChequeEspecial**:
Valor máximo que o Cliente pode utilizar além do saldo disponível na ContaCorrente. Produto de
crédito único do escopo.
_Evitar_: Cheque especial, Crédito rotativo, Limite

**LimiteChequeEspecialVigente**:
O LimiteChequeEspecial atualmente reconhecido pelo CoreLegado. É o único valor que pode ser
apresentado como "o limite do Cliente" (ADR-0002).
_Evitar_: LimiteVigente, Limite aprovado, Limite atual

**LimiteSolicitado**:
Valor pretendido em uma SolicitacaoAumentoLimite. Não se torna LimiteChequeEspecialVigente antes da
EfetivacaoLimite.

**IncrementoSolicitado**:
Diferença entre o LimiteSolicitado e o LimiteChequeEspecialVigente. Um dos dois eixos da
AlcadaAprovacao.

**SolicitacaoAumentoLimite**:
Pedido de aumento do LimiteChequeEspecial de uma ContaCorrente, registrado pelo
GerenteRelacionamento a partir de manifestação do Cliente. Agregado central do contexto. Uma
ContaCorrente possui no máximo uma SolicitacaoAumentoLimite em estado não terminal — regra que
atravessa instâncias do agregado e, por isso, não é imposta por uma delas isoladamente.
_Evitar_: Proposta, Pedido, Alteração de limite

**OrigemSolicitacao**:
Quem originou a solicitação. Hoje possui um único valor — CLIENTE —, estabelecido pelo domínio e
nunca aceito do cliente HTTP: o GerenteRelacionamento registra o que o Cliente manifestou, e não uma
iniciativa própria. Simplificação deliberada: não modela consentimento formal nem assinatura
eletrônica.
_Evitar_: Consentimento, Anuência, Autorização, Canal

**ManifestacaoCliente**:
Como a manifestação do Cliente chegou ao GerenteRelacionamento: o CanalManifestacao e uma observação
opcional do gerente. Distinta da OrigemSolicitacao, que diz quem originou. Junto com o originadorId
e o instante de registro, é a evidência operacional de que a solicitação partiu do Cliente — e
deliberadamente não prova jurídica.
_Evitar_: Consentimento, Origem, Solicitação do cliente

**CanalManifestacao**:
Meio pelo qual o Cliente manifestou o pedido ao gerente: PRESENCIAL, TELEFONE ou CANAL_DIGITAL.

**StatusSolicitacaoAumentoLimite**:
Estado operacional do processo, distinto do resultado da DecisaoCredito. Existem apenas os estados
que o slice atual exercita: SOLICITADA, AGUARDANDO_EFETIVACAO, EFETIVACAO_INDETERMINADA, EFETIVADA,
REJEITADA e FALHA_EFETIVACAO (ADR-0010). Os três primeiros são não terminais.
_Evitar_: APROVADA como status — aprovação é resultado de decisão, não estado de workflow

**EFETIVACAO_INDETERMINADA**:
Estado não terminal em que a SolicitacaoAumentoLimite entra quando a recuperação automática se
esgota sem resposta autoritativa do CoreLegado. Afirma ignorância, e não falha: o limite pode ter
sido efetivado. Continua bloqueando nova solicitação para a mesma ContaCorrente e continua
recuperável — um callback ou uma consulta posterior o conclui em EFETIVADA ou FALHA_EFETIVACAO. Seu
contrário é FALHA_EFETIVACAO, que exige evidência autoritativa de que a efetivação não ocorreu
(ADR-0009).
_Evitar_: FALHA_EFETIVACAO para ausência de resposta, Timeout, Pendente, Em processamento

**Histórico funcional**:
Trilha append-only por SolicitacaoAumentoLimite (tabela `historico_solicitacao`), cada linha uma
EntradaHistorico com um TipoFatoHistorico, o AtorOperacao responsável e o instante do fato. Explica
como se chegou ao estado atual; **não** é Event Sourcing, não é Kafka, não é servico-auditoria e
não reconstrói o agregado — o estado atual continua persistido pelo modelo normal
(SolicitacaoAumentoLimite, ContextoDecisaoCredito, DecisaoCredito). Cada entrada tem identidade
estável derivada do fato causador, suficiente para deduplicar sob replay/redelivery: o mesmo fato
lógico nunca produz uma segunda entrada. #0003 registra `SOLICITACAO_REGISTRADA` e
`DECISAO_AUTOMATICA_REGISTRADA`; os fatos de efetivação (`EFETIVACAO_SOLICITADA` e demais)
pertencem a #0004+, pela mesma regra que rege StatusSolicitacaoAumentoLimite (ADR-0010): o
vocabulário técnico só nasce quando o comportamento que o produz existe.
_Evitar_: Event Sourcing, Auditoria, Log de eventos, Reconstrução do agregado

**ContextoDecisaoCredito**:
Fotografia imutável dos fatos considerados no momento da submissão, junto com a
`versaoPoliticaCredito` aplicada. Guarda indicadores derivados e sua procedência, nunca os dados
brutos que os originaram (ADR-0006). Contém somente o que a decisão usou e o que permite
reproduzi-la: a identidade do sujeito e a verificação do direito de atendimento não entram — elas
pertencem à SolicitacaoAumentoLimite, porque respondem "quem podia operar", e não "com quais fatos
se decidiu".
_Evitar_: Snapshot, Dados do cliente, Payload da proposta

**SituacaoConta**:
A situação da ContaCorrente reduzida à pergunta que a PoliticaCredito precisa fazer: regular, ou
não. O CoreLegado mantém uma gradação mais fina (`SIT-CTA`: regular, bloqueada, encerrada); a ACL
própria de Credito traduz qualquer código diferente de regular para irregular — inclusive um
código que o host venha a introduzir depois, porque o desconhecido nunca deve virar "regular" por
omissão. Não é apresentada ao GerenteRelacionamento como texto do host: o que a decisão comunica é
o MotivoDecisaoCredito (`CONTA_NAO_ELEGIVEL`), nunca o status bruto.
_Evitar_: SIT-CTA, Status da conta, Situação cadastral

**ClassificacaoRiscoCreditoBase**:
Classificação de risco simples que o CoreLegado já mantém para a operação bancária corrente —
BAIXO, MEDIO ou ALTO —, consultada por Credito pela sua própria ACL (ADR-0004) e congelada no
ContextoDecisaoCredito. É insumo da PoliticaCredito, e não produto do contexto Risco: não se confunde
com AvaliacaoRisco nem com ResultadoAvaliacaoRisco, que são processamento especializado, caro e
assíncrono. Não é apresentada ao GerenteRelacionamento: é insumo interno da política, e o que a
decisão comunica é o MotivoDecisaoCredito, nunca a gradação.
_Evitar_: Score, Rating, ResultadoAvaliacaoRisco, Risco do cliente

**DadosCreditoCore**:
Os fatos de crédito lidos do CoreLegado numa única consulta — LimiteChequeEspecialVigente,
situacaoConta e ClassificacaoRiscoCreditoBase —, junto com o instante da consulta e a identificação
da fonte. Existe para que a procedência dentro do ContextoDecisaoCredito seja registrada uma vez
para o conjunto, e não repetida campo a campo.
_Evitar_: Snapshot do Core, Dados do cliente, Payload do Core

**PoliticaCredito**:
Conjunto fictício de regras que classifica uma SolicitacaoAumentoLimite. É versionada, porque toda
DecisaoCredito registra sob qual versão foi tomada.

**MotorDecisaoCredito**:
Mecanismo automatizado que aplica a PoliticaCredito vigente ao ContextoDecisaoCredito. Decide
crédito nas situações em que a política permite decisão automática, e sinaliza quando o caso exige
avaliação adicional ou análise humana.
_Evitar_: Engine, Regras, Motor de risco

**ParecerCredito**:
Manifestação **humana** do AnalistaCredito sobre uma solicitação, fundamentada no
ContextoDecisaoCredito, no resultado do MotorDecisaoCredito e no ResultadoAvaliacaoRisco quando
existir. Recomenda; não decide.
_Evitar_: Análise de crédito, Avaliação, Opinião

**DecisaoCredito**:
Decisão com consequência formal sobre a solicitação — APROVADA ou REJEITADA. Produzida pelo
MotorDecisaoCredito quando a política permite, ou por ator humano autorizado quando há necessidade
de decisão humana. Registra autor, instante, motivo, política aplicada, evidências e, quando
humana, a AlcadaAplicada.
_Evitar_: Aprovação, Resultado, Deferimento

**MotivoDecisaoCredito**:
Código estável que diz por que a DecisaoCredito foi o que foi. É vocabulário do domínio, e nunca
frase de interface: o texto apresentado ao GerenteRelacionamento pertence ao app-gerente. Motivos de
rejeição não são intercambiáveis — FORA_DA_POLITICA_AUTOMATICA afirma apenas que a versão vigente da
PoliticaCredito não concede automaticamente aquela solicitação, e não que o Cliente tenha risco ruim
ou seja inelegível.
_Evitar_: Mensagem, Descrição da recusa, Erro

**AlcadaAprovacao**:
Autoridade para decidir uma solicitação, medida simultaneamente em dois eixos: o LimiteSolicitado
absoluto e o IncrementoSolicitado. Autoriza; não roteia (ADR-0008).
_Evitar_: Permissão, Papel, Limite de aprovação

**PerfilAlcadaAprovacao**:
Perfil nomeado e versionado que define uma AlcadaAprovacao e possui vigência própria. Existe para
que a autoridade não seja nem um papel codificado em condicional, nem números soltos gravados em
cada usuário.

**AtribuicaoAlcada**:
Vínculo, com vigência própria, entre um AtorHumano e um PerfilAlcadaAprovacao. Permite mudar a
autoridade de uma pessoa sem tocar em sua identidade.
_Evitar_: Papel, Permissão do usuário

**AlcadaAplicada**:
Registro, dentro da DecisaoCredito, da autoridade efetivamente exercida: qual perfil, qual versão e
quais eixos estavam vigentes naquele instante. Existe para que alterar um PerfilAlcadaAprovacao
hoje não reescreva o significado de uma decisão tomada ontem (ADR-0008).
_Evitar_: Alçada do aprovador, Nível de aprovação

**EfetivacaoLimite**:
Aplicação de uma DecisaoCredito aprovada no CoreLegado. Só após a confirmação do Core o
LimiteSolicitado se torna o LimiteChequeEspecialVigente (ADR-0002, ADR-0009). A instrução leva o
LimiteChequeEspecialVigente sobre o qual a decisão foi tomada como precondição: se o Core já não
estiver nesse estado, a alteração não é aplicada silenciosamente por cima.
_Evitar_: Aprovação, Conclusão, Aplicação

**EfetivacaoId**:
Identidade de negócio da tentativa lógica de efetivação, criada por Credito quando a intenção é
registrada duravelmente, e estável por toda a vida dela. É a chave de idempotência perante o
CoreLegado — reenviar a mesma instrução não aplica a alteração duas vezes — e permite recuperar o
resultado quando o aceite se perdeu antes de o ProtocoloCore ser conhecido. É distinto do
identificador técnico da mensagem no Outbox (messageId) — que identifica a mensagem registrada, não
a operação perante o Core — mas os dois são igualmente estáveis: um reenvio nunca gera nem troca
nenhum dos dois.
_Evitar_: Id técnico da efetivação

**ProtocoloCore**:
Identificador devolvido pelo CoreLegado ao aceitar uma instrução de EfetivacaoLimite. É o que
permite perguntar depois "o que aconteceu com aquela instrução": o callback o traz de volta, e a
reconciliação consulta o status por ele quando o callback não chega (ADR-0009). Pode existir sem ser
conhecido por Credito, quando a resposta de aceite se perde; nesse caso a recuperação é pelo
EfetivacaoId.
_Evitar_: Protocolo, Id da transação, Ticket


## Sobre procedência: instante de consulta não é data de atualização na fonte

Distinção que o contrato host-centric torna fácil de confundir e que a ACL mantém separada
(ADR-0005): `consultadoEm`, dentro de `DadosCreditoCore`, é o instante em que **esta plataforma**
capturou os fatos do CoreLegado com sucesso — é o que responde "estes fatos são de agora?". O
host também informa quando **ele** atualizou o limite; isso é informação da fonte sobre a fonte,
permanece encapsulada na ACL, e não é derivada nem derivável de `consultadoEm`.

## Deployable

`fk-servico-credito`, porta 8083. Resource Server que também atua como OAuth client: ao continuar
a operação em nome do usuário contra `servico-carteira-clientes`, apresenta token obtido por Token
Exchange com `aud = servico-carteira-clientes` e scope reduzido a `carteira.leitura` — a cadeia de
delegação estreita capability, nunca amplia (ADR-0015).
