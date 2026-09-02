# Dados pertencem ao contexto, privilégio pertence ao processo, migração é etapa de deployment

ADR-0011 proíbe banco compartilhado entre serviços. A precisão que faltava: **a propriedade dos
dados é do bounded context, não do processo**. `servico-movimentacoes` e `batch-movimentacoes`
compartilham `movimentacoes_db` porque são o mesmo contexto; `servico-credito` alcançar esse banco
continua proibido, e a integração cruza obrigatoriamente uma API ou um contrato assíncrono.

Cada contexto persistente tem database, credencial e conjunto de migrations próprios. Uma única
instância PostgreSQL hospedando vários databases isolados é otimização do ambiente local, e não
afirmação sobre produção: a decisão arquitetural é armazenamento privado, e a localização física do
servidor é decisão operacional posterior.

Database só aparece quando o comportamento exige estado durável. O `bff-gerente` não recebe database
de domínio — sua sessão vive em Redis, armazenamento técnico privado dele (ADR-0015). Isso não é
promessa de que `servico-notificacoes` jamais terá persistência: quando precisar registrar tentativa,
entrega, retry ou histórico, ganha o seu.

## Privilégio por processo

Compartilhar o database dentro do contexto não obriga a compartilhar credencial.
`movimentacoes_api_user` e `movimentacoes_batch_user` existem separados para limitar blast radius: o
processo online recebe o necessário à API, o batch recebe staging, promoção, reconciliação, Outbox e
a metadata do Spring Batch — que vive em schema técnico, separada das tabelas de negócio.

## Migração não acontece na inicialização da aplicação

O motivo não é corrida: Flyway coordena execuções concorrentes por lock. O motivo é privilégio — se
a aplicação migra ao subir, ela precisa de DDL em runtime, para sempre. **Migração é etapa explícita
de deployment**, executada por um executor dedicado antes de subir a versão correspondente: migrar,
subir a API, liberar o batch. Localmente isso é um container one-shot no Compose; em CI/CD, uma
etapa do pipeline.

As aplicações não migram: verificam que o schema é compatível e falham rápido quando não é. As
tabelas de metadata do Spring Batch são persistentes, migradas explicitamente como quaisquer outras
e nunca criadas implicitamente pelo framework — sem elas não há restart, histórico de JobExecution
nem reprocessamento controlado.

## Consequências

Nenhum processo de aplicação tem privilégio DDL em runtime, e a ordem de deployment passa a fazer
parte do contrato de release.

Ferramenta de consulta não lê banco de negócio por atalho: Grafana consulta os backends de
observabilidade, nunca `credito_db` ou `movimentacoes_db` (ADR-0017). Reporting autoritativo, quando
existir, será uma projeção criada explicitamente para essa finalidade.

## Emenda 2026-09-02: migração embutida para contextos de baixa escala

Para #0001, o executor de migrations dedicado (processo/deployable separado) provou-se peso
desproporcional para dois scripts SQL rodando numa única réplica: um módulo Maven inteiro, imagem
Docker própria e um passo extra de orquestração no Compose, só para preservar uma garantia — nenhum
processo com DDL em runtime — cujo risco real, nesta escala, é teórico: não há réplicas concorrentes,
e o blast radius de um processo comprometido numa POC educacional não pesa o mesmo que em produção.

A partir desta emenda, um contexto que não precise de coordenação de deployment multi-réplica pode
migrar via Flyway embutido no próprio processo, usando `spring.flyway.*` com credencial de DDL
(migrator) **distinta** de `spring.datasource.*` (credencial de DML da aplicação) — Spring Boot
resolve essas duas propriedades como `DataSource`s independentes. Isso preserva a separação de
privilégio por credencial (a query da aplicação nunca usa a credencial de DDL), mas abre mão da
garantia mais forte de "nenhum processo com DDL configurado, nem dormente": a credencial de migrator
passa a existir na configuração do processo que também serve tráfego, mesmo que usada só no boot.

Continua valendo, sem mudança: dados pertencem ao contexto; privilégio é separado por credencial
(mesmo que agora dentro do mesmo processo); e nenhuma aplicação decide seu próprio schema
implicitamente — toda mudança é script Flyway versionado, nunca DDL ad-hoc.

Um contexto que justifique a garantia mais forte — múltiplas réplicas do processo online, ou postura
de segurança que não tolere credencial de DDL em processo que serve tráfego — volta ao executor
dedicado. Isso é decisão por contexto, avaliada quando o contexto nasce, não regra única obrigatória
para o repositório inteiro.
