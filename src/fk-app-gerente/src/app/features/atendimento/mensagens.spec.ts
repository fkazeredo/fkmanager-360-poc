import {
  acaoParaErroSubmissao,
  apresentacaoDeStatus,
  mensagemDecisao,
  mensagemDeErro,
  mensagemDeErroSubmissao,
} from './mensagens';

/**
 * A tabela exaustiva da taxonomia de erro da submissao, testada pura (sem TestBed): cada par
 * status/codigo relevante -> a acao sobre a Idempotency-Key e a mensagem. Os EFEITOS dessas acoes
 * no componente (zerar a key, refetch, bloquear formulario) continuam provados nos specs de
 * componente (atendimento-submissao.spec.ts), que atravessam HTTP de verdade.
 */
describe('acaoParaErroSubmissao', () => {
  it('409 LIMITE_VIGENTE_DESATUALIZADO -> nova chave + refetch (limiteDesatualizado)', () => {
    const acao = acaoParaErroSubmissao(409, 'LIMITE_VIGENTE_DESATUALIZADO');
    expect(acao.tipo).toBe('limiteDesatualizado');
    expect(acao.mensagem).toContain('O limite mudou');
  });

  it('409 SOLICITACAO_NAO_TERMINAL_EXISTENTE -> bloquear formulario, chave preservada', () => {
    const acao = acaoParaErroSubmissao(409, 'SOLICITACAO_NAO_TERMINAL_EXISTENTE');
    expect(acao.tipo).toBe('bloquearFormulario');
    expect(acao.mensagem).toContain('processo em andamento');
  });

  it('409 IDEMPOTENCIA_EM_PROCESSAMENTO -> manter chave (retry transparente com a MESMA key)', () => {
    const acao = acaoParaErroSubmissao(409, 'IDEMPOTENCIA_EM_PROCESSAMENTO');
    expect(acao.tipo).toBe('manterChave');
    expect(acao.mensagem).toContain('sendo processada');
  });

  it('409 sem codigo conhecido -> manter chave com mensagem generica', () => {
    expect(acaoParaErroSubmissao(409, undefined).tipo).toBe('manterChave');
  });

  it.each([502, 503, 504])('%i -> manter chave e mensagem unica de indisponibilidade (AC37)', (status) => {
    const acao = acaoParaErroSubmissao(status, undefined);
    expect(acao.tipo).toBe('manterChave');
    expect(acao.mensagem).toBe('Nao foi possivel concluir a operacao agora, tente novamente.');
  });

  it.each([400, 422])('%i -> manter chave e mensagem generica de comando invalido', (status) => {
    const acao = acaoParaErroSubmissao(status, undefined);
    expect(acao.tipo).toBe('manterChave');
    expect(acao.mensagem).toBe('Nao foi possivel processar esta solicitacao. Tente novamente.');
  });

  it('403 e 404 reaproveitam as mensagens definitivas de mensagemDeErro', () => {
    expect(acaoParaErroSubmissao(403, undefined).mensagem).toBe(mensagemDeErro(403));
    expect(acaoParaErroSubmissao(404, undefined).mensagem).toBe(mensagemDeErro(404));
  });
});

describe('mensagemDeErroSubmissao', () => {
  it('LIMITE_VIGENTE_DESATUALIZADO nao passa por aqui -- caso generico do 409', () => {
    // O fluxo proprio (nova chave + refetch) pertence a acaoParaErroSubmissao.
    expect(mensagemDeErroSubmissao(409, 'LIMITE_VIGENTE_DESATUALIZADO'))
      .toBe('Nao foi possivel concluir a operacao agora, tente novamente.');
  });
});

describe('mensagemDecisao', () => {
  const decisao = (
    resultado: 'APROVADA' | 'REJEITADA',
    motivo: 'DENTRO_DA_POLITICA_AUTOMATICA' | 'CONTA_NAO_ELEGIVEL' | 'PERFIL_RISCO_INCOMPATIVEL' | 'FORA_DA_POLITICA_AUTOMATICA',
  ) => ({ resultado, motivo, versaoPoliticaCredito: 'v1', decididaEm: '2026-09-04T12:00:00Z' });

  it('APROVADA', () => {
    expect(mensagemDecisao(decisao('APROVADA', 'DENTRO_DA_POLITICA_AUTOMATICA')))
      .toBe('Solicitacao aprovada automaticamente.');
  });

  it('FORA_DA_POLITICA_AUTOMATICA preserva a semantica exata da spec (AC37)', () => {
    const mensagem = mensagemDecisao(decisao('REJEITADA', 'FORA_DA_POLITICA_AUTOMATICA'));
    expect(mensagem).toBe('Esta solicitacao nao se enquadra na politica de concessao automatica vigente.');
    expect(mensagem).not.toContain('risco');
    expect(mensagem).not.toContain('cadastr');
  });

  it('CONTA_NAO_ELEGIVEL e PERFIL_RISCO_INCOMPATIVEL nunca expoem classificacao bruta (AC3)', () => {
    expect(mensagemDecisao(decisao('REJEITADA', 'CONTA_NAO_ELEGIVEL'))).toContain('nao esta elegivel');
    expect(mensagemDecisao(decisao('REJEITADA', 'PERFIL_RISCO_INCOMPATIVEL'))).not.toMatch(/BAIXO|MEDIO|ALTO/);
  });
});

describe('apresentacaoDeStatus (#0006, AC37)', () => {
  it('EFETIVACAO_INDETERMINADA e tom acompanhamento, nunca erro', () => {
    const apresentacao = apresentacaoDeStatus('EFETIVACAO_INDETERMINADA');
    expect(apresentacao.tom).toBe('acompanhamento');
    expect(apresentacao.tom).not.toBe('erro');
  });

  it('EFETIVACAO_INDETERMINADA avisa que uma nova solicitacao para a conta nao pode ser iniciada', () => {
    expect(apresentacaoDeStatus('EFETIVACAO_INDETERMINADA').mensagem).toContain(
      'nova solicitacao para esta conta nao pode ser iniciada',
    );
  });

  it('EFETIVACAO_INDETERMINADA nao afirma que efetivou nem que falhou', () => {
    const mensagem = apresentacaoDeStatus('EFETIVACAO_INDETERMINADA').mensagem;
    expect(mensagem).not.toMatch(/falhou|falha/i);
    expect(mensagem).not.toMatch(/efetivada com sucesso|foi efetivad/i);
  });

  it('EFETIVADA e tom sucesso', () => {
    expect(apresentacaoDeStatus('EFETIVADA').tom).toBe('sucesso');
  });

  it.each(['FALHA_EFETIVACAO', 'REJEITADA'])('%s e tom erro', (status) => {
    expect(apresentacaoDeStatus(status).tom).toBe('erro');
  });

  it('AGUARDANDO_EFETIVACAO e tom acompanhamento, como EFETIVACAO_INDETERMINADA', () => {
    expect(apresentacaoDeStatus('AGUARDANDO_EFETIVACAO').tom).toBe('acompanhamento');
  });

  it('status desconhecido nunca lanca -- cai no tom neutro com o proprio valor como mensagem', () => {
    const apresentacao = apresentacaoDeStatus('UM_STATUS_QUE_NAO_EXISTE_AINDA');
    expect(apresentacao.tom).toBe('neutro');
    expect(apresentacao.mensagem).toBe('UM_STATUS_QUE_NAO_EXISTE_AINDA');
  });
});
