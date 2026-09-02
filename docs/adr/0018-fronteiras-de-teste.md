# Fronteiras de teste: regra de negócio sem infraestrutura, integração sem simulacro

O critério que organiza a suíte: **se uma regra de negócio só consegue ser testada subindo
infraestrutura, há forte indício de que a regra está no lugar errado**. Maker-checker (ADR-0007),
alçada em dois eixos (ADR-0008), a máquina de estados da solicitação e a `PoliticaCredito` são
testados em JUnit puro, em milissegundos, sem Spring, PostgreSQL, Docker, OAuth ou broker. Esse é o
retorno concreto do hexágono; sem ele, "hexagonal" seria apenas um arranjo de pastas.

A camada de aplicação é testada sem Spring sempre que possível, com as portas de saída substituídas
por fakes ou stubs pequenos — fake comportamental simples é preferível a árvores extensas de mocks
quando torna o teste legível. O objeto ali é orquestração, não regra.

## Adapters são provados contra a coisa real

O critério para adapters não é "todo método precisa de container", e sim: **o comportamento de
integração é provado contra a infraestrutura ou o protocolo real**. Persistência roda contra
PostgreSQL via Testcontainers, sem H2 fingindo comportamento equivalente. RabbitMQ e Kafka reais
cobrem publicação, consumo, ack, redelivery, idempotência, retry, DLQ, headers, partition key,
consumer groups e serialização. Mapper e conversor puros continuam sendo teste unitário.

A ACL do Core não é testada mockando `RestClient`. Um mock HTTP server exercita o mesmo client usado
em produção e produz as patologias que dão sentido à camada (ADR-0005): `200` com `COD-RET = 117`,
timeout, connection reset, campo em branco, zero-padding inesperado, data inválida, código de
retorno desconhecido, payload malformado. Um conjunto pequeno roda contra o `simulador-core-legado`
real, para detectar deriva entre o contrato que o simulador implementa e o que o adapter espera.

Cada serviço tem poucos testes verticais com contexto Spring completo, provando o caminho adapter →
aplicação → domínio → adapter. Eles não recombinam regras de negócio. ArchUnit cobre o estrutural:
domínio independente de frameworks, aplicação independente de adapters, adapters apontando para
dentro, ausência de JPA, Kafka e Rabbit dentro do domínio, e ausência de dependência Java entre
bounded contexts.

Segurança tem dois níveis: testes de serviço usam JWT controlado para verificar audience, scopes,
papel e autorização de recurso; os poucos E2E globais autenticam de verdade.

## E2E prova a topologia, não a regra

Playwright cobre de três a cinco jornadas — straight-through approval, rejeição automática, callback
perdido recuperado pela reconciliação e, quando os slices existirem, risco com decisão humana. Não
existe `disable-security-for-tests`: o E2E atravessa cookie, CSRF, Authorization Code + PKCE, sessão
e Token Exchange, porque a autenticação é parte do que se quer provar (ADR-0015). Trinta e sete
combinações de `AlcadaAprovacao` pertencem ao domínio, no teste mais barato disponível.

Isso impõe um componente: o `simulador-core-legado` precisa de um control plane próprio, ativo
apenas em profiles como `local`, `demo` e `test`, capaz de configurar sucesso, `COD-RET` de erro,
timeout, atraso, callback atrasado, duplicado ou suprimido, falha definitiva e resposta malformada.
Sem ele, `FALHA_EFETIVACAO` e a reconciliação de ADR-0009 não são demonstráveis. Essa interface é
deliberadamente separada do contrato host-centric e não faz parte da interface funcional do
simulador.

O batch não é testado por Playwright: tem suíte própria de aceitação — arquivo legado, execução
one-shot, staging, validação, reconciliação, promoção, Outbox e Kafka.

## Consequências

O custo de escrever um teste vira sinal de design: quando cobrir uma regra exige container, a
primeira hipótese é que a regra vazou para fora do domínio.

O simulador passa a ter duas interfaces com propósitos distintos, e confundi-las — expor controle de
cenário como se fosse capacidade do Core — destruiria o valor da simulação.
