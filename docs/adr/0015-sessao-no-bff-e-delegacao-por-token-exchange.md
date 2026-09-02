# Sessão no BFF, tokens fora do browser, delegação por Token Exchange

O `bff-gerente` é OAuth2 confidential client e conduz Authorization Code + PKCE + OIDC contra o
`servidor-autorizacao`. O browser recebe apenas um cookie de sessão opaco — `Secure`, `HttpOnly`,
`SameSite` restritivo compatível com o fluxo OIDC e, quando a topologia permitir, prefixo `__Host-`.
Access token, refresh token e client secret nunca chegam ao Angular. Como o browser autentica por
cookie, proteção CSRF é requisito explícito, e `SameSite` sozinho não é tratado como defesa
suficiente.

O BFF **não é conceitualmente stateless**: ele guarda a autorização OAuth do usuário. Esse estado
vive em Redis via Spring Session, para que o BFF escale horizontalmente sem sticky session e
qualquer instância recupere a mesma sessão. Redis é armazenamento técnico privado do BFF, não banco
de domínio (ADR-0014).

## Tokens são audience-restricted

Não existe token de usuário multi-audience circulando pela plataforma. Cada Resource Server recebe
um token emitido para ele e valida localmente assinatura, issuer, expiração, `aud` e scopes via
JWKS. Quando o BFF precisa chamar `servico-carteira-clientes`, obtém por **OAuth 2.0 Token
Exchange** um token delegado com `aud = servico-carteira-clientes`; para `servico-credito`, outro.

A mesma regra vale para chamada encadeada. Ao montar o `ContextoDecisaoCredito`, `servico-credito`
continua a operação em nome do usuário e precisa que `CarteiraClientes` verifique o direito de
atendimento (ADR-0007) — então ele atua como OAuth client e troca o token que recebeu por outro, com
`aud = servico-carteira-clientes`. Reutilizar o token destinado a Crédito o transformaria em
credencial de plataforma, que é exatamente o que esta decisão evita.

Token Exchange não implica uma ida ao `servidor-autorizacao` por chamada HTTP downstream: tokens
delegados podem ser reutilizados dentro de sua curta validade quando subject, audience, scopes e
contexto de autorização forem equivalentes, com cache server-side nunca exposto ao browser. A saída
proibida é a oposta — emitir tokens longos para não precisar trocar.

## O que o token não carrega

Scopes representam capacidades grossas — `carteira.leitura`, `credito.escrita` — e nunca política de
negócio: `credito.aprovar-ate-50000` não existe. OAuth responde se aquela identidade pode tentar
acessar a capacidade; o domínio responde se aquela operação específica é permitida.

Por isso `PerfilAlcadaAprovacao` e `AtribuicaoAlcada` pertencem a `Credito`, e não ao
`servidor-autorizacao`, que conhece identidade, autenticação e papéis organizacionais grossos.
Alçada não viaja em claim: uma sessão iniciada horas antes congelaria autorização financeira
vencida, e ADR-0008 exige registrar na decisão a `AlcadaAplicada` vigente naquele instante.

## Identidades não-humanas

Chamada HTTP entre sistemas sem usuário usa `client_credentials`, com client próprio por processo
chamador e token igualmente audience-restricted — o callback do Core chega ao `servico-credito`
autenticado assim. A autenticação preferida dos clients técnicos é assimétrica, via
`private_key_jwt`, evitando espalhar segredos estáticos de longa duração como desenho final;
`client_secret_basic` fica para conveniência local, e mTLS permanece hardening possível, não
mecanismo acumulado por acumular. Que o `simulador-core-legado` fale OAuth não contradiz ADR-0005:
o que é legado ali é a semântica do contrato, não o transporte.

OAuth só entra quando uma fronteira HTTP autenticada é atravessada. Um `@Scheduled` de reconciliação
ou um dispatcher lendo a própria Outbox estão dentro do processo e não emitem token contra si
mesmos — no domínio eles são AtorSistema. Perante RabbitMQ e Kafka, cada serviço usa sua credencial
técnica de broker, com ACL de publicação e consumo. Access token de usuário não viaja em mensagem,
Outbox nem arquivo: token é autorização de acesso efêmera, não identidade histórica durável.
Mensagens carregam `atorId`, `tipoAtor`, `correlationId` e `causationId` como metadado de negócio e
auditoria, jamais como credencial.

## Consequências

Um token vazado alcança um serviço, não a plataforma inteira.

O `servidor-autorizacao` passa a estar no caminho quente das chamadas delegadas, o que torna cache
de tokens delegados e observação da sua latência requisitos, e não otimizações posteriores.

Cada deployable novo que chama outro precisa de identidade de client própria — custo aceito
conscientemente para que "quem chamou" seja sempre uma pergunta respondível.
