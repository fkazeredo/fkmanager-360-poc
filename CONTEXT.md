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

O mapa dos contextos — responsabilidades, relações, papel estratégico e estado de materialização de
cada um — está em `CONTEXT-MAP.md`, na raiz. Para um contexto ainda não materializado, este arquivo
é o glossário; para um já materializado, seu vocabulário específico vive em
`docs/contextos/<contexto>/CONTEXT.md`, conforme `docs/agents/domain.md`. **Atores**, abaixo, é vocabulário
compartilhado e por isso permanece aqui mesmo depois de um contexto materializar.

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
manifestada pelo Cliente e acompanha seu andamento, inclusive o das solicitações que originou para
um Cliente que depois deixou sua CarteiraClientes: acesso ao processo e acesso atual ao Cliente são
regras distintas, e a primeira não concede a segunda. Nunca decide sobre a solicitação que ele
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

Materializado (ticket #0001). Vocabulário próprio em `docs/contextos/identidade-e-acesso/CONTEXT.md`. Seu
vocabulário de negócio é o da seção **Atores** acima: os papéis reconhecidos pelo sistema.

## CarteiraClientes

Materializado (ticket #0001). Vocabulário próprio em `docs/contextos/carteira-clientes/CONTEXT.md`.

## Credito

Materializado (ticket #0002). Vocabulário próprio em `docs/contextos/credito/CONTEXT.md`.

## Risco

Supporting domain. Contexto especializado em avaliar risco, que **não aprova crédito**: produz
informação para que Credito prossiga. Não se confunde com a ClassificacaoRiscoCreditoBase, que é
insumo simples e pré-existente do CoreLegado consultado por Credito.

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
se torna o LimiteChequeEspecialVigente.

**MovimentacaoImportada**:
Fato publicado por Movimentacoes ao final de uma importação. Detalhamento pertence à spec do slice
que introduzir o contexto.

**CorrelationId** / **CausationId**:
Metadado de negócio no envelope de toda mensagem. O `correlationId` identifica a **jornada de
negócio**, que atravessa HTTP, Outbox, RabbitMQ, Kafka, callback e reconciliação por horas ou dias;
o `causationId` identifica a mensagem imediatamente anterior que causou esta. Nenhum dos dois é
`traceId`, que é execução técnica e tem outro ciclo de vida (ADR-0017). O `correlationId` de uma
jornada de crédito nasce em Credito, no instante em que a SolicitacaoAumentoLimite é efetivamente
criada, e não na borda web: o `bff-gerente` participa do trace técnico, mas não é dono do processo.
Também não é a Idempotency-Key, que identifica uma tentativa de submissão, e não a jornada.
Mensagens carregam também `atorId` e `tipoAtor` como metadado de auditoria — jamais token ou
credencial (ADR-0015).
