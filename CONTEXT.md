# fk-manager-360

Aplicação web interna usada por gerentes de relacionamento de um banco fictício. Permite consultar
a carteira de clientes e sua posição financeira, e conduzir o processo de aumento de limite de
cheque especial — da solicitação à efetivação no core legado.

Ambiente bancário fictício: nenhuma regra de banco real e nenhum dado de cliente real.

## Como ler este glossário

A linguagem ubíqua é **pt-BR** (ADR-0001). Identificadores não levam acento nem cedilha
(`Alcada`, `AvaliacaoRisco`); a prosa deste arquivo leva.

Os termos estão agrupados por bounded context (ADR-0003). Um mesmo conceito pode aparecer em mais
de um contexto com significado próprio — `ContaCorrente` em `CarteiraClientes` não é a mesma coisa
que em `Movimentacoes`, e isso é deliberado.

O mapa dos contextos — responsabilidades, relações e papel estratégico de cada um — está em
`CONTEXT-MAP.md`, na raiz. Enquanto nenhum contexto foi materializado em código, este arquivo é o
glossário consolidado; quando um contexto for materializado, seu vocabulário se move para o
`CONTEXT.md` do próprio contexto, conforme `docs/agents/domain.md`.

## Atores

**AtorOperacao**:
Quem responde por uma operação registrada pelo sistema. É sempre um AtorHumano ou um AtorSistema,
nunca ausente. Autoria de negócio e execução técnica não se confundem: uma DecisaoCredito tomada
por um SupervisorCredito continua sendo dele mesmo quando a EfetivacaoLimite é executada por outro
ator e confirmada pelo CoreLegado.
_Evitar_: Usuário, Responsável, Origem

**AtorHumano**:
Pessoa identificada exercendo um dos papéis reconhecidos pelo sistema.

**AtorSistema**:
Componente que executa uma operação sem pessoa por trás, identificado por nome próprio —
MOTOR_DECISAO_CREDITO, RECONCILIACAO_EFETIVACAO, BATCH_MOVIMENTACOES, CORE_LEGADO. Existe para que
operação automática tenha autor real, e não um ator humano vazio.
_Evitar_: Sistema, Automático, Job

**GerenteRelacionamento**:
Funcionário do banco responsável por uma CarteiraClientes. Registra no sistema a solicitação
manifestada pelo Cliente e acompanha seu andamento. Nunca decide sobre a solicitação que ele
próprio originou (ADR-0007).
_Evitar_: Gerente de Contas, Gestor, Operador

**AnalistaCredito**:
Pessoa que examina as solicitações que a decisão automática não resolveu e produz um
ParecerCredito. Não efetiva limite e, isoladamente, não decide.
_Evitar_: Analista de Risco, Analista

**SupervisorCredito**:
Pessoa com AlcadaAprovacao formal, que aprova ou rejeita solicitações sujeitas a decisão humana.
_Evitar_: Aprovador, Gerente Regional

**AdministradorSistema**:
Ator responsável pela carga de parâmetros e pela configuração necessária à demonstração.

**Cliente**:
Pessoa física titular de uma ContaCorrente. **Não é usuário do fk-manager-360**: é o sujeito da
operação, e sua solicitação chega ao sistema pela mão do GerenteRelacionamento.
_Evitar_: Usuário, Correntista

**CoreLegado**:
Conjunto de sistemas legados da instituição, representado de forma simplificada por um único
simulador. É autoritativo para o estado financeiro operacional da conta (ADR-0002). Quando confirma
uma efetivação, age como AtorSistema.
_Evitar_: Mainframe, Legado, Backend

## IdentidadeEAcesso

Contexto responsável por autenticar o ator e emitir as credenciais de acesso da plataforma. Seu
vocabulário de negócio é o da seção **Atores** acima: os papéis reconhecidos pelo sistema.

Este contexto conhece identidade, autenticação e papéis organizacionais grossos. **Autoridade
financeira não mora aqui**: `PerfilAlcadaAprovacao` e `AtribuicaoAlcada` pertencem a Credito, e
alçada nunca viaja em claim de token (ADR-0015). Scope responde se aquela identidade pode tentar
acessar uma capacidade; o domínio responde se aquela operação específica é permitida.

## CarteiraClientes

Contexto responsável por responder quem é o cliente, quais contas ele possui, e qual gerente tem
direito de atendê-lo.

**CarteiraClientes**:
Conjunto de Clientes sob responsabilidade de um GerenteRelacionamento. A associação pertence ao
fk-manager-360; os dados mestres do Cliente, não.
_Evitar_: Base de clientes, Book, Portfólio

**ContaCorrente**:
Conta de um Cliente. Neste contexto é essencialmente a conta que pertence a determinado Cliente e
que determinado gerente tem direito de atender.

**PosicaoFinanceira**:
Retrato consolidado da situação da ContaCorrente em um instante: saldo, LimiteVigente e exposição.
_Evitar_: Saldo, Extrato

## Credito

Core domain. Responsável pelo processo de aumento de limite: solicitação, política, decisão,
alçada e efetivação.

**LimiteChequeEspecial**:
Valor máximo que o Cliente pode utilizar além do saldo disponível na ContaCorrente. Produto de
crédito único do escopo.
_Evitar_: Cheque especial, Crédito rotativo, Limite

**LimiteVigente**:
O LimiteChequeEspecial atualmente reconhecido pelo CoreLegado. É o único valor que pode ser
apresentado como "o limite do Cliente" (ADR-0002).
_Evitar_: Limite aprovado, Limite atual

**LimiteSolicitado**:
Valor pretendido em uma SolicitacaoAumentoLimite. Não se torna LimiteVigente antes da
EfetivacaoLimite.

**IncrementoSolicitado**:
Diferença entre o LimiteSolicitado e o LimiteVigente. Um dos dois eixos da AlcadaAprovacao.

**SolicitacaoAumentoLimite**:
Pedido de aumento do LimiteChequeEspecial de uma ContaCorrente, registrado pelo
GerenteRelacionamento a partir de manifestação do Cliente. Agregado central do contexto.
_Evitar_: Proposta, Pedido, Alteração de limite

**OrigemSolicitacao**:
Registro de que a solicitação partiu do Cliente e de como ela chegou ao gerente. Simplificação
deliberada: não modela consentimento formal nem assinatura eletrônica.
_Evitar_: Consentimento, Anuência, Autorização

**StatusSolicitacaoAumentoLimite**:
Estado operacional do processo, distinto do resultado da DecisaoCredito. Existem apenas os estados
que o slice atual exercita: SOLICITADA, AGUARDANDO_EFETIVACAO, EFETIVADA, REJEITADA e
FALHA_EFETIVACAO (ADR-0010).
_Evitar_: APROVADA como status — aprovação é resultado de decisão, não estado de workflow

**ContextoDecisaoCredito**:
Fotografia imutável dos fatos considerados no momento da submissão, com a versão da política
aplicada. Guarda indicadores derivados e sua procedência, nunca os dados brutos que os originaram
(ADR-0006).
_Evitar_: Snapshot, Dados do cliente, Payload da proposta

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
LimiteSolicitado se torna o LimiteVigente (ADR-0002, ADR-0009).
_Evitar_: Aprovação, Conclusão, Aplicação

**ProtocoloCore**:
Identificador devolvido pelo CoreLegado ao aceitar uma instrução de EfetivacaoLimite. É o que
permite perguntar depois "o que aconteceu com aquela instrução": o callback o traz de volta, e a
reconciliação consulta o status por ele quando o callback não chega (ADR-0009).
_Evitar_: Protocolo, Id da transação, Ticket

## Risco

Supporting domain. Contexto especializado em avaliar risco, que **não aprova crédito**: produz
informação para que Credito prossiga.

**AvaliacaoRisco**:
Processamento automatizado, mais caro e assíncrono, acionado quando o MotorDecisaoCredito não
conclui. Possui identidade e ciclo de execução próprios — pedir a mesma avaliação duas vezes não
cria duas avaliações.
_Evitar_: Análise de risco, AnaliseRisco, Score

**ResultadoAvaliacaoRisco**:
Produto de uma AvaliacaoRisco: classificação, score calculado, fatores relevantes, versão do
modelo utilizado e instante da avaliação.
_Evitar_: Parecer, Nota

## Movimentacoes

Contexto de lançamentos e importação legada. Entra no slice 4; o vocabulário abaixo é
**provisório** e será fechado pela spec que o introduzir.

**MovimentacaoConta** / **LancamentoConta**:
A distinção entre os dois **ainda não está resolvida** — registrada aqui como pendência
consciente, não como sinonímia aceita.

**ArquivoMovimento**:
Arquivo posicional recebido do CoreLegado contendo os lançamentos de uma DataMovimento.

**LoteImportacao**:
Unidade de processamento de um ArquivoMovimento, com identidade e resultado próprios.

**ReconciliacaoLote**:
Conferência entre o que o CoreLegado enviou e o que foi de fato projetado localmente.

**OcorrenciaReconciliacao**:
Divergência individual apurada por uma ReconciliacaoLote.

**DataMovimento**:
Data contábil à qual um conjunto de lançamentos pertence, que não coincide necessariamente com a
data em que o arquivo foi processado.

## Comandos e eventos de negócio

Nomes fechados em ADR-0016. Comandos são intenção e vão no imperativo; eventos são fato ocorrido e
vão no passado. O que atravessa a fronteira é sempre contrato versionado, nunca agregado, entidade
JPA ou DTO interno (ADR-0019).

**AvaliarRisco**:
Comando de Credito para Risco, pedindo que uma AvaliacaoRisco aconteça. Tem destinatário funcional
conhecido e trafega em RabbitMQ.

**AvaliacaoRiscoConcluida**:
Fato publicado por Risco quando uma AvaliacaoRisco termina. Risco não responde a Credito: publica o
que aconteceu no seu próprio contexto, e quem tem interesse consome.

**DecisaoCreditoRegistrada**:
Fato publicado por Credito quando uma DecisaoCredito passa a existir.

**LimiteEfetivado**:
Fato publicado por Credito quando o CoreLegado confirma uma EfetivacaoLimite e o LimiteSolicitado
se torna o LimiteVigente.

**MovimentacaoImportada**:
Fato publicado por Movimentacoes ao final de uma importação. Detalhamento pertence à spec do slice
que introduzir o contexto.

**CorrelationId** / **CausationId**:
Metadado de negócio no envelope de toda mensagem. O `correlationId` identifica a **jornada de
negócio**, que atravessa HTTP, Outbox, RabbitMQ, Kafka, callback e reconciliação por horas ou dias;
o `causationId` identifica a mensagem imediatamente anterior que causou esta. Nenhum dos dois é
`traceId`, que é execução técnica e tem outro ciclo de vida (ADR-0017). Mensagens carregam também
`atorId` e `tipoAtor` como metadado de auditoria — jamais token ou credencial (ADR-0015).
