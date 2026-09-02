# O batch é disparado de fora e lê de uma landing zone

`batch-movimentacoes` é processo one-shot: inicia, executa o Job e termina com exit status coerente
com o resultado (ADR-0013). Um processo com essa forma **não agenda a si mesmo** — não existe
`@Scheduled` embutido esperando a madrugada dentro dele, porque isso o transformaria em processo
contínuo e destruiria a própria razão de ele ser one-shot.

O agendamento é, portanto, responsabilidade de um **scheduler externo ao processo**. Localmente,
esse papel é do Docker Compose — container one-shot invocado sob demanda ou por profile, do mesmo
modo que o executor de migrações (ADR-0014). Em ambiente real, é o agendador da instituição ou do
orquestrador; qual deles é decisão operacional, e escolhê-la agora seria antecipar infraestrutura
que nenhuma spec pediu (ADR-0010, ADR-0012).

Isto **não** contradiz ADR-0009. A reconciliação de efetivações roda dentro do `servico-credito`,
que é processo contínuo, e pode usar o scheduling do próprio Spring desde que seja segura sob
execução repetida ou concorrente. A distinção é entre agendar trabalho dentro de um processo que já
está de pé e agendar a **existência** de um processo finito.

## Landing zone

O arquivo posicional do CoreLegado (ADR-0005) chega a uma **landing zone**: um diretório observável
pelo batch, montado como volume no Compose. O batch lê de lá; não busca o arquivo por conta própria
em interface de rede do Core, e a landing zone não é uma segunda porta de integração online.

O arquivo é insumo **imutável**: nunca é editado no lugar. Depois de processado, sai da área de
entrada para uma área de resultado — processados ou rejeitados — para que a segunda execução não
reprocesse silenciosamente o que a primeira já consumiu. A identidade do trabalho é do
`LoteImportacao`, e não do caminho do arquivo; reprocessar é ato explícito, não efeito colateral de
o arquivo continuar no diretório.

Convenção de nomes, retenção, arquivamento, deduplicação e a estratégia de restart pertencem à spec
do slice que introduzir a importação (ADR-0010). O que esta decisão fixa é a forma da fronteira, não
seus detalhes.

## Consequências

O exit status do batch é o contrato com quem o agenda: falha precisa ser visível para o scheduler,
e não apenas registrada em log.

Nenhum processo do sistema precisa de eleição de líder para não duplicar trabalho agendado — o
disparo é único por construção, e a reconciliação online resolve concorrência por claim
transacional (ADR-0009).

A landing zone entra na topologia de execução como volume, e passa a ser parte do que um ambiente
precisa oferecer para que o slice de importação seja demonstrável (ADR-0013, ADR-0018).
