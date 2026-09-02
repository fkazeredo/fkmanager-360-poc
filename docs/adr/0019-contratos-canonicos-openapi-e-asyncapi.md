# Um artefato canônico por contrato: OpenAPI para REST, AsyncAPI para mensageria

Monorepo não impede quebra de contrato, e não é esse o argumento. A decisão é outra: **existe um
único artefato canônico por interface, e não uma segunda DSL de contrato concorrendo com ele.**

REST tem OpenAPI versionado no repositório, servindo simultaneamente de documentação, base do teste
de conformidade do provider, base de validação do consumer, detector de breaking change e, quando
vantajoso, fonte de geração de cliente. Mensageria tem AsyncAPI como catálogo da interface
assíncrona, referenciando os schemas dos payloads — JSON Schema para os eventos atuais — e
especificando envelope, payload, tipos, obrigatoriedade, enumerações e compatibilidade.

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

O diretório de contratos vira o inventário do que precisaria acompanhar cada serviço numa eventual
extração para repositório próprio.
