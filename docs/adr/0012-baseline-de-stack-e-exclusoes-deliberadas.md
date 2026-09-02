# Baseline de stack, exclusões deliberadas e postura de escala

A stack é atual, estável e corporativa. Backend: Java 25 LTS, Spring Boot 4.x, Spring MVC como
modelo HTTP padrão, Spring Security, Spring Authorization Server, Spring Data, Spring Batch,
Spring for Apache Kafka, Spring AMQP, PostgreSQL, Flyway. Frontend: Angular 22, standalone APIs,
zoneless, Signals para estado reativo onde fizer sentido — RxJS permanece permitido onde
representar naturalmente streams e composição assíncrona, sem regra artificial de "tudo deve ser
Signal". Testes: JUnit, ArchUnit, Testcontainers com PostgreSQL, Kafka e RabbitMQ reais ao testar
esses adapters, e Playwright para E2E. Observabilidade: Micrometer, OpenTelemetry, Actuator.

**Virtual threads não são religião arquitetural.** Podem ser habilitadas nos serviços Spring MVC
com I/O bloqueante e avaliadas com testes; não se assume que virtual threads sejam sempre mais
rápidas. Spring Batch, consumers e trabalhos CPU-bound são avaliados conforme sua própria
natureza, e não recebem virtual threads apenas porque a aplicação roda em Java 25. Quando
habilitadas, observa-se comportamento, throughput e casos de pinning.

## Fora do baseline

WebFlux, arquitetura reativa fim-a-fim, GraalVM Native Image, Debezium, CDC, event sourcing e
service mesh. Não são proibidos para sempre: **uma tecnologia nova entra quando uma necessidade
funcional ou não funcional concreta a justificar** — Debezium se surgir requisito real de CDC,
WebFlux se surgir problema que justifique programação reativa, Native Image se startup ou memória
virarem requisito. Até lá, a solução permanece moderna, convencional e compreensível.

## Escala

A arquitetura é pensada para instituição de grande porte, mas a execução é sintética e reduzida.
Os volumes da POC são cargas de uma fatia do sistema e **não devem ser apresentados como volume de
um banco inteiro**: dataset pequeno para desenvolvimento, perfil de aproximadamente 100 mil
lançamentos para demonstrar batch, e perfil opcional de 2 milhões ou mais para stress local.

O objetivo é demonstrar que as decisões suportam crescimento — processamento em chunks, paginação,
índices adequados, operações em lote, idempotência, restart de batch, particionamento quando
houver necessidade demonstrável, métricas de throughput, backpressure nos pontos assíncronos, e
ausência de algoritmos que dependam de carregar o lote inteiro em memória.

**Nenhum SLA fictício é estabelecido antecipadamente.** Nada de "dois milhões em cinco minutos"
antes de medir.
