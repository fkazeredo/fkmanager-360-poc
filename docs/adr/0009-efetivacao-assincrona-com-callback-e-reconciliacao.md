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
