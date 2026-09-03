# Spring Data JPA é o padrão de persistência; Lombok vive fora do hexágono

Três decisões de implementação que valiam por convenção implícita passam a ser explícitas, porque a
primeira delas contradiz o que o código fazia até aqui.

## Persistência relacional usa Spring Data JPA; SQL é exceção justificada

`CarteiraClientes` e `Credito` nasceram com `JdbcClient` e SQL escrito à mão. Para operação
relacional convencional — leitura por chave, existência, paginação, persistência de entidade,
histórico, contexto, decisão, idempotência, Outbox — isso passa a ser errado por padrão: usa-se
`@Entity`, repository Spring Data, query derivation, JPQL e projections **antes** de escrever
infraestrutura JDBC.

SQL nativo, `JdbcClient` ou `EntityManager` cru continuam permitidos, mas exigem **razão técnica
concreta, documentada no ponto de uso**. As razões que já se materializaram neste repositório:

- `FOR UPDATE NOWAIT`, e a identificação precisa do `SQLState` que ele produz. Em `Credito`, TX2
  adquire o lock por `JdbcClient` porque o comportamento de exceção foi verificado empiricamente
  (`55P03` chega como `UncategorizedSQLException`, não como `CannotAcquireLockException`) e trocar o
  mecanismo mudaria o tipo da exceção sem que nenhum teste de unidade percebesse.
- Ordem física de statements quando ela é semântica de negócio. Em TX1, a ordem
  `solicitacao → contexto → idempotencia → historico` decide qual constraint colide primeiro sob
  concorrência; Hibernate reordena escritas no flush, então `persist`/`saveAndFlush` explícito por
  passo é obrigatório — e se essa disciplina tornasse a implementação menos legível que o JDBC
  equivalente, o JDBC teria permanecido.

O critério é esse: **JPA é o padrão, não uma meta de cobertura.** Quando as duas alternativas
existem, ganha a que o teste de concorrência aprova e o leitor entende — não a que usa mais
framework.

Flyway continua soberano sobre o schema. `spring.jpa.hibernate.ddl-auto` é `validate`, nunca
`create`, `update` ou `create-drop`; Hibernate valida contra o schema que a migration criou e falha
rápido quando o mapeamento diverge. A separação de credenciais de ADR-0014 não é afetada: Hibernate
usa exclusivamente o `DataSource` de runtime (credencial de DML), e o Flyway continua com a sua
credencial de DDL. `spring.jpa.open-in-view` é `false` — lazy loading fora da transação é defeito,
não conveniência.

Entidade JPA não é objeto de domínio. O modelo de persistência vive em
`adapter/out/persistence/entity`, o modelo de domínio continua onde estava, e a tradução entre os
dois é escrita à mão (`de(...)` / `toDomain()`), sem biblioteca de mapeamento — sete agregados não
justificam um gerador, e um mapper gerado esconderia exatamente os três casos que importam:
`AtorOperacao` selado virando duas colunas, `ManifestacaoCliente` virando duas, `DadosCreditoCore`
achatado em cinco.

## Lombok é permitido, e não entra no hexágono

Lombok remove ruído — `@RequiredArgsConstructor`, `@Getter`, `@Slf4j`, `@NoArgsConstructor` em
entidade JPA. Não substitui record nem value object, e `@Data` em entidade é proibido: gera
`equals`/`hashCode`/`toString` que atravessam relação lazy e assumem identidade que a entidade pode
não ter.

**Lombok vale em `adapter/**` e `config/**`, e em nenhum outro lugar.** `domain/**` e
`application/**` continuam Java puro, sem processador de anotação. A razão não é estética: Lombok é
ferramenta de infraestrutura, não elemento nativo de arquitetura hexagonal, e o interior do hexágono
é justamente onde a ausência de dependência externa é a propriedade que se quer preservar
(ADR-0020). O enforcement é automatizado — `lombok.addLombokGeneratedAnnotation` faz o Lombok marcar
cada membro gerado com `@lombok.Generated`, e uma regra ArchUnit em cada serviço falha se essa
anotação aparecer dentro do hexágono. A regra foi mantida porque se provou falsificável de verdade,
não porque soava bem.

## O frontend local usa a porta 4200, sobre TLS

`fk-app-gerente` é servido em **`https://localhost:4200`** — nginx, com TLS, mesma origem que
`/bff/**`, `/oauth2/**` e `/login`.

É HTTPS, e não `http://localhost:4200`, porque a arquitetura de segurança exige: o cookie de sessão
do BFF é `Secure` (ADR-0015), e o E2E prova esse atributo contra a stack real. Servir o Angular em
HTTP puro tornaria o cookie não-`Secure` — regressão de uma garantia já provada desde #0001. Servir
o Angular por `ng serve` em outra origem quebraria same-origin e exigiria CORS mais um segundo
`redirect_uri` registrado.

A porta em si é livre porque certificado X.509 nunca amarra porta: o mesmo certificado `CN=localhost`
serve em 4200 como servia em 443. Mudou o número, e mais nada — same-origin, TLS, `Secure`, CSRF,
PKCE e Token Exchange seguem idênticos.

## Consequências

Trocar de tecnologia de acesso a dados deixa de ser reescrita: o domínio nunca soube que existia
`JdbcClient`, e continua sem saber que existe Hibernate.

`ddl-auto: validate` transforma divergência entre entidade e migration em falha de boot, não em erro
de runtime meses depois — mas exige que o mapeamento seja preciso quanto ao tipo físico da coluna, e
`CHAR(64)` já provou isso na prática.

Toda ocorrência de SQL manual que sobreviver a partir daqui carrega, no próprio código, a explicação
de por que JPA não serviu. Quando essa explicação não existir, o SQL está errado.
