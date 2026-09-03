# Um artefato canônico por contrato: OpenAPI para REST, AsyncAPI para mensageria

Monorepo não impede quebra de contrato, e não é esse o argumento. A decisão é outra: **existe um
único artefato canônico por interface, e não uma segunda DSL de contrato concorrendo com ele.**

REST tem OpenAPI versionado no repositório (`src/<serviço>/openapi.yaml`), servindo simultaneamente
de documentação, base do teste de conformidade do provider, base de validação do consumer, detector
de breaking change e, quando vantajoso, fonte de geração de cliente. Mensageria tem AsyncAPI como
catálogo da interface assíncrona, referenciando os schemas dos payloads — JSON Schema para os
eventos atuais — e especificando envelope, payload, tipos, obrigatoriedade, enumerações e
compatibilidade.

**O arquivo é gerado, não escrito à mão.** Cada serviço Spring Boot anota seus controllers e DTOs de
`adapter/in/web` (springdoc/swagger: `@Operation`, `@ApiResponse`, `@Schema`, `@SecurityRequirement`
— nunca em `domain`/`application`, mesma fronteira do hexágono de sempre) e expõe `/v3/api-docs.yaml`
publicamente (sem autenticação — é documentação, não dado de negócio; permitido explicitamente no
`SecurityConfig` de cada serviço). `openapi.yaml` é esse mesmo documento, capturado com o serviço no
ar (local ou via Docker Compose) e commitado como qualquer outro artefato gerado — regenerar é rodar
o serviço e substituir o arquivo pela resposta atual do endpoint, nunca editar o YAML à mão. Isso
elimina a classe de bug em que o contrato descreve um comportamento que o código já não tem: o único
jeito de o contrato divergir do código é esquecer de regenerar, um erro de processo detectável por
diff, não um erro de digitação silencioso.

O que a anotação NÃO carrega é o raciocínio de negócio por trás de cada regra — por que um `409`
precede um `422`, por que um campo é excluído do corpo, por que uma resposta trata como equivalentes
duas patologias distintas. Essa camada de "porquê" já vive em `docs/specs/` e nos ADRs, e duplicá-la
em `description`s de anotação criaria uma segunda cópia fadada a divergir da primeira — exatamente o
tipo de deriva documental que este repositório já pagou o preço de corrigir uma vez (ADR-0009 versus
a spec, sobre a estabilidade do `messageId`). O contrato gerado documenta o que um consumidor
precisa tecnicamente saber — paths, schemas, status codes, segurança — e remete à spec/ADR
correspondente quando a nuance importa.

O CI verifica a evolução: acrescentar campo opcional passa; remover campo obrigatório, renomear
campo, alterar tipo, reduzir enum aceito ou mudar a semântica mantendo a mesma versão não passam
silenciosamente.

Consumidores testam contra o contrato público, nunca importando a classe Java do produtor. Importar
`AvaliacaoRiscoConcluida` de `servico-risco` seria compartilhamento de implementação disfarçado de
contrato, e ADR-0011 proíbe exatamente isso.

## O que fica de fora, e por quê

Spring Cloud Contract não é obrigatório no baseline, porque hoje seria uma segunda fonte de verdade
do mesmo contrato. Não está proibido: entra se houver benefício concreto em contratos
consumer-driven e stubs gerados, o que fica bem mais provável se o repositório virar polyrepo
(ADR-0011).

Schema Registry também não entra, e isso é decisão consciente e não simplificação de slice: o
monorepo, os schemas versionados e o CI compartilhado já impõem a governança de que precisamos. Ele
passa a ser justificável quando houver produtor externo, consumidor fora do nosso CI, times
independentes ou necessidade de enforcement de compatibilidade em runtime. Kafka existir não é razão
para adotá-lo (ADR-0012).

## Consequências

Publicar um evento novo implica escrever o contrato dele: não existe evento inter-contexto sem
schema versionado.

O OpenAPI de cada serviço HTTP mora dentro do próprio serviço (`src/<serviço>/openapi.yaml`), e
não num diretório central de contratos: assim o arquivo já viaja junto numa eventual extração para
repositório próprio (ADR-0011), sem inventário separado para lembrar de levar.
