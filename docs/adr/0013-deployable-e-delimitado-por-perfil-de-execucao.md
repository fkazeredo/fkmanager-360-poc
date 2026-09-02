# Deployable é delimitado por perfil de execução, não por bounded context

A topologia alvo é `app-gerente`, `bff-gerente`, `servidor-autorizacao`, `servico-carteira-clientes`,
`servico-credito`, `servico-risco`, `servico-movimentacoes`, `batch-movimentacoes`,
`servico-auditoria`, `servico-notificacoes` e `simulador-core-legado`. A lista é o desenho alvo;
cada deployable é materializado quando uma spec exigir sua existência (ADR-0010).

ADR-0003 já dizia que o mapa de contextos não é o organograma dos deployables. Esta decisão é o
outro lado da mesma moeda: o que delimita um processo executável é o perfil de execução — latência
contra throughput, contínuo contra finito, escala horizontal contra lote. `Movimentacoes` é o caso
demonstrativo: `servico-movimentacoes` atende consultas com baixa latência e disponibilidade
contínua, enquanto `batch-movimentacoes` é processo one-shot que inicia, executa um Job e termina
com exit status coerente com o resultado. São dois deployables e continua sendo **um** bounded
context.

## BFF é delimitado pela experiência cliente, não por persona

Enquanto existir uma única aplicação Angular, existe um único `bff-gerente`. A entrada de
AnalistaCredito e SupervisorCredito não cria um segundo BFF: personas diferentes usam áreas
diferentes do mesmo frontend. Outro BFF só se justifica quando surgir outra experiência cliente com
ciclo de vida próprio — outro frontend, aplicação mobile, time e cadência independentes, ou
necessidade de agregação substancialmente distinta.

O BFF é fronteira web e de composição: sessão do usuário, tokens fora do browser, endpoints
adaptados à tela, composição quando a tela realmente exigir. Não implementa regra de crédito, não
decide alçada nem maker-checker, não possui agregado nem Outbox de negócio, não acessa banco de
outro serviço, não fala com o `simulador-core-legado` e não substitui a autorização de recurso feita
pelos serviços (ADR-0004, ADR-0007). Se a tela não precisa de composição, encaminhar é resposta
legítima — o que não o transforma em API Gateway genérico.

## Nome do servidor de autorização

O deployable chama-se `servidor-autorizacao` porque sua responsabilidade é ser Authorization Server
OAuth2/OIDC. O bounded context continua sendo `IdentidadeEAcesso`; um nome como `servico-identidade`
prometeria pelo nome uma plataforma de IAM que não estamos construindo.

## Execução local

Docker Compose é o contrato oficial de execução integrada, com imagem própria por deployable e
profiles para que ninguém seja obrigado a subir tudo o tempo inteiro. Também é suportado subir a
infraestrutura pelo Compose e executar um serviço na IDE contra ela — o que proíbe configuração
dependente de hostname resolvível apenas dentro da rede Docker. Kubernetes, Helm e manifests ficam
fora até que um requisito operacional concreto os justifique (ADR-0012).

## Consequências

Acrescentar um deployable não acrescenta um contexto, e a contagem de processos nunca deve ser
apresentada como contagem de contextos.

Como cada processo é fronteira de execução, ele é também fronteira de identidade e de privilégio:
processo novo tem identidade técnica própria (ADR-0015) e credencial de banco própria quando acessa
um (ADR-0014).
