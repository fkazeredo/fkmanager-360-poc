# Mapa de contextos — fk-manager-360

Ponto de entrada da documentação de domínio deste repositório. ADR-0003 é a **decisão** que
estabelece quais são os contextos; este arquivo é o **mapa** derivado dela: responsabilidades,
relações, papel estratégico e estado de materialização de cada um.

Este mapa **não é o organograma dos deployables** (ADR-0003, ADR-0013). Contar processos não é
contar contextos.

## Como ler a documentação de domínio

- **Este arquivo** (`CONTEXT-MAP.md`): quais contextos existem e como se relacionam.
- **`CONTEXT.md` na raiz**: glossário consolidado, hoje ainda único, com os termos agrupados por
  contexto. É a fonte de vocabulário enquanto nenhum contexto foi materializado em código.
- **`docs/adr/`**: decisões sistêmicas, válidas para todo o repositório.

A linguagem ubíqua é pt-BR (ADR-0001). Identificadores não levam acento nem cedilha; a prosa leva.

## Bounded contexts

### IdentidadeEAcesso

Responde quem é o ator, autentica-o e emite as credenciais de acesso da plataforma. Conhece
identidade, autenticação e papéis organizacionais grossos; **não** conhece política de negócio —
`PerfilAlcadaAprovacao` e `AtribuicaoAlcada` pertencem a `Credito`, não a este contexto (ADR-0015).

Seu deployable previsto é o `servidor-autorizacao` (ADR-0013), ainda não materializado: o nome é de
Authorization Server porque é isso que ele faz, e o bounded context continua sendo
`IdentidadeEAcesso`.

### CarteiraClientes

Responde quem é o Cliente, quais ContaCorrente ele possui e qual GerenteRelacionamento tem direito
de atendê-lo. É a **autoridade sobre a associação atual** GerenteRelacionamento ↔ Carteira ↔
Cliente, e verifica esse direito ao ser acessado (ADR-0007). Os dados mestres do Cliente pertencem
ao CoreLegado; a associação pertence ao fk-manager-360.

### Credito — **Core Domain**

O domínio central da POC. Responde pelo processo de aumento do LimiteChequeEspecial de ponta a
ponta: solicitação, PoliticaCredito, MotorDecisaoCredito, ParecerCredito, DecisaoCredito,
AlcadaAprovacao e EfetivacaoLimite. Guarda os invariantes que justificam o projeto — maker-checker
(ADR-0007), alçada em dois eixos (ADR-0008), fotografia imutável do ContextoDecisaoCredito
(ADR-0006) e conclusão idempotente da efetivação (ADR-0009).

### Risco — **Supporting Domain**

Contexto especializado que **pontua** e não aprova crédito: produz `ResultadoAvaliacaoRisco` para
que `Credito` prossiga. Separado de `Credito` porque faz coisa diferente — processamento mais caro
e assíncrono, com identidade e ciclo de execução próprios — e não porque seria uma camada técnica
do core (ADR-0003).

### Movimentacoes

Contexto de lançamentos, importação legada e reconciliação. Fala uma língua que nenhum outro
contexto fala: ArquivoMovimento, LoteImportacao, DataMovimento, ReconciliacaoLote. Conhecer uma
`ContaCorrente` não o obriga a compartilhar modelo com `CarteiraClientes` — ali a conta é o vínculo
com um Cliente; aqui é referência para lançamentos, períodos, saldos e reconciliação (ADR-0003).

Seu vocabulário no `CONTEXT.md` raiz é declaradamente **provisório** e será fechado pela spec que o
introduzir; este mapa não o antecipa.

Dois deployables servem este contexto — `servico-movimentacoes`, contínuo, e `batch-movimentacoes`,
one-shot disparado por scheduler externo e alimentado por uma landing zone — e continua sendo **um**
bounded context (ADR-0013, ADR-0021).

## Relações e dependências conhecidas

Entidades de domínio Java nunca são compartilhadas entre contextos (ADR-0003, ADR-0011). Toda
integração cruza uma API ou um contrato assíncrono explícito, jamais um banco (ADR-0014).

| De | Para | Natureza | Referência |
| --- | --- | --- | --- |
| `Credito` | `CarteiraClientes` | Síncrona. Identidade e relacionamento para montar o `ContextoDecisaoCredito`, e verificação do direito de atendimento. `Credito` atua como OAuth client e troca o token recebido por um com `aud = servico-carteira-clientes`. | ADR-0004, ADR-0007, ADR-0015 |
| `Credito` | `Risco` | Assíncrona por **comando**: `Credito` publica `AvaliarRisco` em RabbitMQ quando o `MotorDecisaoCredito` não conclui. | ADR-0016 |
| `Risco` | `Credito` | Assíncrona por **fato**: `Risco` publica `AvaliacaoRiscoConcluida` em Kafka dentro do seu próprio contexto; `Credito` decide que tem interesse. `Risco` **não responde** a `Credito` e não conhece seus consumidores. | ADR-0016 |
| `Credito` | `Risco` | Síncrona, **apenas recuperação**: quando o caminho assíncrono falha em silêncio, `Credito` consulta o estado autoritativo de `Risco` por REST idempotente. Não substitui o fluxo assíncrono. | ADR-0016 |
| `Credito` | `Movimentacoes` | **Prevista, não existente.** Indicadores financeiros para compor o `ContextoDecisaoCredito` quando `Movimentacoes` surgir. | ADR-0004 |
| `IdentidadeEAcesso` | todos | Upstream de autenticação. Emite tokens audience-restricted por Resource Server; scopes representam capacidades grossas e nunca política de negócio. | ADR-0015 |
| cada contexto | `CoreLegado` | Cada contexto integra o legado **pela sua própria ACL**, apenas na fatia que lhe diz respeito. Não existe gateway universal nem serviço de integração genérico. | ADR-0002, ADR-0004, ADR-0005 |

O `CoreLegado` é **sistema externo**, não um bounded context deste repositório. É autoritativo para
o estado financeiro operacional da conta (ADR-0002) e aparece no glossário como ator.

## Capacidades transversais — não são bounded contexts

**Auditoria** e **Notificacoes** consomem fatos publicados pelos contextos. Não possuem agregado,
invariante nem decisão, e por isso **não recebem DDD tático** (ADR-0003). Podem existir como
deployables independentes com arquitetura muito mais simples.

O critério de distinção é este: um bounded context tem linguagem própria, agregado e decisão que
lhe pertencem; uma capacidade transversal reage a fatos alheios sem decidir nada de negócio.
Promovê-las a contexto inflaria o mapa e ensinaria exatamente a coisa errada.

## Materialização em código

**Nenhum contexto está materializado em código hoje** — não existe `src/` neste repositório.
Modelo e infraestrutura só aparecem quando uma spec os exige (ADR-0010), e este mapa **não cria
diretórios, serviços ou scaffolding** para acomodar documentação futura.

| Contexto | Materializado | Observação |
| --- | --- | --- |
| `IdentidadeEAcesso` | não | Exercitado pelo slice 1 (autenticação do gerente). Seu vocabulário de negócio é o dos Atores; autoridade financeira pertence a `Credito`. |
| `CarteiraClientes` | não | Exercitado pelo slice 1. |
| `Credito` | não | Exercitado pelo slice 1; é o contexto com vocabulário mais desenvolvido. |
| `Risco` | não | Entra quando uma spec introduzir decisão que o `MotorDecisaoCredito` não conclui. |
| `Movimentacoes` | não | Previsto para o slice 4; vocabulário provisório. |

## Estratégia de glossário durante a transição

Enquanto um contexto não for materializado, seu vocabulário permanece na seção correspondente do
`CONTEXT.md` raiz, que serve como **glossário consolidado**.

Quando um contexto for materializado em código, seu vocabulário específico é **movido** para o
`CONTEXT.md` do próprio contexto (`src/<contexto>/CONTEXT.md`) e este mapa passa a apontar para
lá. A migração acontece contexto a contexto, na spec que o materializa — não antecipadamente, e
não criando arquivos vazios ou especulativos.

Decisões sistêmicas permanecem em `docs/adr/`. Decisões específicas de um contexto materializado
podem viver em `src/<contexto>/docs/adr/`, conforme `docs/agents/domain.md`.
