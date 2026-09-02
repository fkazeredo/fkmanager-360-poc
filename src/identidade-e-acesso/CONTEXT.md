# IdentidadeEAcesso

Glossário deste bounded context, materializado pelo ticket #0001 (slice 1). Vocabulário
transversal — **Atores** — permanece no `CONTEXT.md` raiz por ser compartilhado entre contextos
(`docs/agents/domain.md`); o mapa geral está em `CONTEXT-MAP.md`.

A linguagem ubíqua é **pt-BR** (ADR-0001). Identificadores não levam acento nem cedilha; a prosa
deste arquivo leva.

Contexto responsável por autenticar o ator e emitir as credenciais de acesso da plataforma. Seu
vocabulário de negócio é o da seção **Atores** do `CONTEXT.md` raiz: os papéis reconhecidos pelo
sistema.

Este contexto conhece identidade, autenticação e papéis organizacionais grossos. **Autoridade
financeira não mora aqui**: `PerfilAlcadaAprovacao` e `AtribuicaoAlcada` pertencem a Credito, e
alçada nunca viaja em claim de token (ADR-0015). Scope responde se aquela identidade pode tentar
acessar uma capacidade; o domínio responde se aquela operação específica é permitida.

Deployable: `servidor-autorizacao` (ADR-0013) — o nome é de Authorization Server porque é isso que
ele faz; o bounded context continua sendo `IdentidadeEAcesso`.
