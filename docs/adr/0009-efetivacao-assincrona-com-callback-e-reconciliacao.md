# Efetivação assíncrona: outbox, protocolo, callback e reconciliação

A efetivação do limite no CoreLegado é assíncrona. A escolha é deliberada, e não decorre de supor
que core bancário não responde sincronamente — sistemas mainframe modernos podem ser expostos por
APIs síncronas, mensageria ou processamento assíncrono. Modelamos assim porque queremos uma
alteração operacional cuja conclusão pode acontecer independentemente da requisição original e que
precisa sobreviver a falhas de comunicação.

Numa mesma transação PostgreSQL, após uma DecisaoCredito aprovada: registra-se a decisão,
transiciona-se o status para `AGUARDANDO_EFETIVACAO`, e grava-se no Outbox a intenção durável de
efetivação. Depois do commit, um dispatcher envia a instrução ao Core, que responde com aceite e
um protocolo — persistido. A solicitação permanece em `AGUARDANDO_EFETIVACAO`.

O Outbox se justifica pela regra geral, e não pelo legado: *quando uma decisão de negócio
persistida precisa gerar uma instrução ou evento externo de forma confiável, a persistência da
mudança e o registro da mensagem a entregar precisam ocorrer atomicamente*. Ele permanece útil
qualquer que seja o transporte concreto — REST, RabbitMQ ou outro —, porque é o registro durável
da intenção de integração.

## Callback e reconciliação convergem no mesmo caso de uso

O Core confirma o resultado chamando de volta o `servico-credito`. O callback é idempotente:
recebê-lo duas vezes não pode duplicar histórico, provocar nova alteração nem produzir transição
inválida.

Callback não é considerado infalível. Um processo periódico reconcilia as efetivações que
permanecem em `AGUARDANDO_EFETIVACAO` além do prazo esperado, consultando o status por protocolo
no Core. Callback recebido conclui rápido; callback perdido é recuperado pela reconciliação.

**Os dois caminhos usam o mesmo caso de uso idempotente de aplicação** — algo equivalente a
`RegistrarResultadoEfetivacao`. Não existem duas implementações da regra de conclusão.

## Consequências

`AGUARDANDO_EFETIVACAO` passa a ter tempo real de permanência e a sobreviver a restart, o que
justifica sua existência como estado — ao contrário de `APROVADA`, que foi eliminada do workflow
por ser estado sem duração.

Tentativas técnicas de comunicação pertencem ao processo de efetivação, não ao workflow: uma
sequência de timeouts seguida de sucesso mantém a solicitação em `AGUARDANDO_EFETIVACAO` o tempo
todo. `FALHA_EFETIVACAO` representa falha operacional relevante — recuperação automática esgotada
ou falha definitiva devolvida pelo Core — e nunca um timeout transitório.

O agendamento pode usar o scheduling do próprio Spring, mas o trabalho de reconciliação não pode
assumir instância única: precisa ser seguro sob execução repetida ou concorrente, o que pode ser
resolvido por claim transacional dos pendentes no PostgreSQL, sem introduzir infraestrutura de
eleição de líder.

## Emenda — 2026-09-02: identidade da efetivação e resultado indeterminado

A decisão acima permanece válida. O grilling do slice 1 mostrou que ela não fecha dois cenários, e
esta seção os fecha sem revogá-la.

**A instrução carrega identidade de negócio própria.** Junto com o registro durável da intenção,
`Credito` gera um `EfetivacaoId` que viaja na instrução e permanece estável por toda a vida da
operação, inclusive através de reenvios. Ele é distinto do `messageId` do Outbox — que identifica a
mensagem lógica registrada, não a operação de negócio perante o Core — mas os dois são **igualmente
estáveis**: um retry da mesma mensagem preserva ambos (revisão de 2026-09-02, spec, seção
"`EfetivacaoId`, instrução e `ProtocoloCore`"; operacionalizado a partir do #0004, cujo
`outbox_entrega.message_id` é PK/FK 1:1 com `outbox_mensagem` e nunca muda entre tentativas). O que
varia entre tentativas é metadado de entrega — contador de tentativas, timestamps, último erro —
nunca a identidade da mensagem. O CoreLegado deduplica funcionalmente por `EfetivacaoId`: a mesma
instrução reenviada não aplica a alteração duas vezes e devolve o mesmo `ProtocoloCore` quando este
já existe.
Essa deduplicação é comportamento funcional do Core, e não capacidade do control plane de cenários
(ADR-0018).

**Perder a resposta de aceite não impede a recuperação.** O texto original diz que a reconciliação
consulta o status por protocolo, o que pressupõe que o protocolo é conhecido por `Credito` — e ele
pode existir apenas no Core, quando a resposta de aceite se perde. A consulta de status é
recuperável por `ProtocoloCore` quando conhecido e por `EfetivacaoId` quando não, e o callback
também correlaciona por `EfetivacaoId`, o que permite que um callback chegue antes de o aceite ter
sido persistido e ainda assim encontre a operação. A fronteira entre os dois mecanismos é estrita: o
dispatcher **entrega** a instrução e pode reenviá-la mantendo o mesmo `EfetivacaoId`; o reconciliador
apenas **pergunta** o resultado, e nunca reenvia.

**A efetivação é condicionada ao estado sobre o qual se decidiu.** A instrução leva o
`LimiteChequeEspecialVigenteEsperado` — o vigente congelado no `ContextoDecisaoCredito` — como
precondição. Se o Core já não estiver nesse estado, a alteração não é aplicada por cima: o retorno
divergente é falha definitiva daquela efetivação, e o caminho correto é uma nova
`SolicitacaoAumentoLimite`. Reenviar recalculando sobre o novo vigente seria tomar uma segunda
decisão de crédito sem que exista uma.

**Ausência de resposta não é evidência de falha.** A frase original — "`FALHA_EFETIVACAO` representa
falha operacional relevante — recuperação automática esgotada ou falha definitiva devolvida pelo
Core" — fica superada na sua primeira metade. `FALHA_EFETIVACAO` passa a exigir evidência
autoritativa de que a efetivação não ocorreu: retorno definitivo do Core, callback de falha, ou
consulta de reconciliação que responda falha. Esgotar tentativas, tomar timeout, receber `COD-RET`
desconhecido ou não conseguir consultar o Core durante um período não dizem nada sobre o limite — o
Core pode ter efetivado. Registrar isso como falha gravaria um fato possivelmente falso num processo
financeiro.

Para esse caso existe `EFETIVACAO_INDETERMINADA`, estado **não terminal** em que a solicitação entra
quando a janela normal de recuperação automática se esgota sem resposta autoritativa. Ele emite
métrica, log estruturado e alerta operacional; continua bloqueando nova solicitação para a mesma
ContaCorrente, porque enquanto não se sabe se o limite mudou autorizar outra alteração seria
inseguro; e continua recuperável — um callback ou uma consulta posterior o conclui em `EFETIVADA` ou
`FALHA_EFETIVACAO`.

Callback e reconciliação seguem convergindo no mesmo caso de uso idempotente
`RegistrarResultadoEfetivacao`, que passa a ser também a única porta de saída de
`EFETIVACAO_INDETERMINADA`.
