# Hexagonal pragmático: a regra de dependência aponta para dentro, e só onde há regra

Cada serviço se organiza em **domínio**, **aplicação** e **adapters**, e a dependência aponta
sempre para dentro. O domínio não importa Spring, JPA, Jackson, Kafka, RabbitMQ, Spring Security
nem cliente HTTP; a aplicação orquestra casos de uso através de portas e não conhece o adapter que
as implementa; adapters de entrada — REST, consumer, batch, teste — e de saída — persistência,
broker, ACL do Core — dependem do que está dentro, nunca o contrário.

A borda traduz. O adapter converte a identidade autenticada em conceito de aplicação, e o domínio
raciocina sobre atores, não sobre tokens (ADR-0007). A ACL converte o vocabulário host-centric em
conceitos do contexto, e nenhum `COD-RET` atravessa para dentro (ADR-0005). DTO de borda e schema
de evento são contrato externo (ADR-0019), não modelo de domínio.

**Pragmático** significa que a estrutura serve ao teste e não à cerimônia. O critério de acerto é
o de ADR-0018: se uma regra de negócio só pode ser testada subindo infraestrutura, ela está no
lugar errado. O recíproco também vale — não se cria porta, adapter ou abstração para algo que não
precisa ser substituído nem isolado, e um mapper puro continua sendo um mapper puro. As capacidades
transversais não recebem hexágono decorativo, pela mesma razão que não recebem DDD tático: não
possuem agregado, invariante nem decisão (ADR-0003).

A regra de dependência não abre exceção para preocupações transversais. Telemetria é uma delas: o
domínio não chama `MeterRegistry` nem `Tracer` (ADR-0017).

O enforcement é automatizado, não cultural: ArchUnit cobre domínio independente de framework,
aplicação independente de adapters, adapters apontando para dentro, ausência de JPA, Kafka e Rabbit
dentro do domínio, e ausência de dependência Java entre bounded contexts (ADR-0011, ADR-0018).

## Consequências

Trocar persistência, transporte ou broker é trabalho de adapter, e a suíte que prova regra de
negócio não é afetada.

O custo de escrever um teste vira sinal de design: quando cobrir uma regra exige container, a
primeira hipótese é que a regra vazou (ADR-0018).

Como a fronteira é verificada por ArchUnit e não por convenção, extrair um serviço para repositório
próprio continua sendo movimento mecânico (ADR-0011).
