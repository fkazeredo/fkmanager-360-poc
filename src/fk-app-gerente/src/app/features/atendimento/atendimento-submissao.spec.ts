import { provideHttpClient, withFetch } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AtendimentoComponent, parseReaisParaCentavos } from './atendimento';
import { ClienteResumo } from '../../core/models';

/**
 * S-Angular (plano #0003, secao 11): submissao de SolicitacaoAumentoLimite e, sobretudo, o
 * lifecycle da Idempotency-Key -- GUARDRAIL explicito: uma manifestacao semantica -> uma key,
 * nunca uma key por tentativa HTTP. Arquivo companheiro de atendimento.spec.ts (mesmo padrao de
 * HttpTestingController), separado para nao misturar a materia da leitura do limite (#0002) com a
 * da submissao (#0003).
 */

const CLIENTE: ClienteResumo = {
  clienteId: '1',
  nome: 'ANA BEATRIZ SOUZA',
  cpfMascarado: '***.222.333-**',
};

const CONTA = { contaId: '10002', agencia: '0001' };

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const SOLICITACOES_URL = '/bff/api/clientes/1/contas/10002/solicitacoes-aumento-limite';
const ATENDIMENTO_URL = '/bff/api/clientes/1/contas/10002/atendimento';

describe('AtendimentoComponent -- submissao de aumento de limite', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AtendimentoComponent],
      providers: [provideHttpClient(withFetch()), provideHttpClientTesting()],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  async function montarComAtendimento(limiteVigenteCentavos: number) {
    const fixture = TestBed.createComponent(AtendimentoComponent);
    fixture.componentRef.setInput('cliente', CLIENTE);
    fixture.detectChanges();

    httpMock.expectOne('/bff/api/clientes/1/contas').flush({ itens: [CONTA] });
    await fixture.whenStable();
    fixture.detectChanges();

    fixture.componentInstance.selecionarConta(CONTA);
    httpMock.expectOne(ATENDIMENTO_URL).flush({
      cliente: CLIENTE,
      conta: CONTA,
      limiteChequeEspecialVigente: limiteVigenteCentavos,
      consultadoEm: '2026-09-02T16:00:00Z',
    });
    await fixture.whenStable();
    fixture.detectChanges();

    return fixture;
  }

  function preencherLimite(fixture: ComponentFixture<AtendimentoComponent>, valor: string) {
    fixture.componentInstance.onLimiteSolicitadoChange(valor);
    fixture.detectChanges();
  }

  function respostaAprovada(overrides: Partial<Record<string, unknown>> = {}) {
    return {
      solicitacaoId: 'sol-1',
      contaId: '10002',
      status: 'AGUARDANDO_EFETIVACAO',
      limiteChequeEspecialVigente: 120000,
      limiteSolicitado: 200000,
      limiteSolicitadoPendenteDeEfetivacao: 200000,
      decisao: {
        resultado: 'APROVADA',
        motivo: 'DENTRO_DA_POLITICA_AUTOMATICA',
        versaoPoliticaCredito: 'v1',
        decididaEm: '2026-09-02T16:01:00Z',
      },
      registradaEm: '2026-09-02T16:01:00Z',
      ...overrides,
    };
  }

  // --- Submissao bem-sucedida ------------------------------------------------------------------

  it('submissao aprovada: envia Idempotency-Key em formato UUID e renderiza vigente + pendente simultaneamente', async () => {
    const fixture = await montarComAtendimento(120000);
    preencherLimite(fixture, '2000,00');

    fixture.componentInstance.submeter();

    const req = httpMock.expectOne(SOLICITACOES_URL);
    expect(req.request.method).toBe('POST');
    const key = req.request.headers.get('Idempotency-Key');
    expect(key).toBeTruthy();
    expect(key).toMatch(UUID_REGEX);
    expect(req.request.body.limiteSolicitado).toBe(200000);
    expect(req.request.body.limiteVigenteVisto).toBe(120000);
    expect(req.request.body.manifestacaoCliente.canalManifestacao).toBe('PRESENCIAL');

    req.flush(respostaAprovada(), { status: 201, statusText: 'Created' });
    await fixture.whenStable();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.decisao.aprovada')).toBeTruthy();
    expect(el.querySelector('.decisao.rejeitada')).toBeNull();
    expect(el.querySelector('.limite-vigente-confirmado')?.textContent).toContain('1.200,00');
    expect(el.querySelector('.limite-pendente')?.textContent).toContain('2.000,00');
    expect(el.querySelector('.status-solicitacao')?.textContent).toContain('AGUARDANDO_EFETIVACAO');
  });

  it('submissao rejeitada por FORA_DA_POLITICA_AUTOMATICA: sem marcacao de pendente, mensagem exata da politica', async () => {
    const fixture = await montarComAtendimento(120000);
    preencherLimite(fixture, '9999,00');

    fixture.componentInstance.submeter();
    const req = httpMock.expectOne(SOLICITACOES_URL);

    req.flush(
      respostaAprovada({
        status: 'REJEITADA',
        limiteSolicitado: 999900,
        limiteSolicitadoPendenteDeEfetivacao: undefined,
        decisao: {
          resultado: 'REJEITADA',
          motivo: 'FORA_DA_POLITICA_AUTOMATICA',
          versaoPoliticaCredito: 'v1',
          decididaEm: '2026-09-02T16:01:00Z',
        },
      }),
      { status: 201, statusText: 'Created' },
    );
    await fixture.whenStable();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.decisao.rejeitada')).toBeTruthy();
    expect(el.querySelector('.decisao.aprovada')).toBeNull();
    expect(el.querySelector('.limite-pendente')).toBeNull();
    expect(el.querySelector('.mensagem-decisao')?.textContent).toContain(
      'nao se enquadra na politica de concessao automatica vigente',
    );
    expect(el.querySelector('.mensagem-decisao')?.textContent).not.toContain('risco');
  });

  it('submissao rejeitada por CONTA_NAO_ELEGIVEL: mensagem propria, sem status host bruto', async () => {
    const fixture = await montarComAtendimento(300000);
    preencherLimite(fixture, '400,00');

    fixture.componentInstance.submeter();
    const req = httpMock.expectOne(SOLICITACOES_URL);
    req.flush(
      respostaAprovada({
        status: 'REJEITADA',
        limiteSolicitado: 40000,
        limiteSolicitadoPendenteDeEfetivacao: undefined,
        decisao: {
          resultado: 'REJEITADA',
          motivo: 'CONTA_NAO_ELEGIVEL',
          versaoPoliticaCredito: 'v1',
          decididaEm: '2026-09-02T16:01:00Z',
        },
      }),
      { status: 201, statusText: 'Created' },
    );
    await fixture.whenStable();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.decisao.rejeitada')).toBeTruthy();
    expect(el.querySelector('.limite-pendente')).toBeNull();
    expect(el.querySelector('.mensagem-decisao')?.textContent).not.toContain('BLOQUEADA');
    expect(el.querySelector('.mensagem-decisao')?.textContent).not.toContain('IRREGULAR');
  });

  // --- Lifecycle da Idempotency-Key (o mais importante) ----------------------------------------

  it('lifecycle da key: duas tentativas seguidas sem editar reusam a MESMA Idempotency-Key (retry apos 503)', async () => {
    const fixture = await montarComAtendimento(120000);
    preencherLimite(fixture, '2000,00');

    fixture.componentInstance.submeter();
    const req1 = httpMock.expectOne(SOLICITACOES_URL);
    const key1 = req1.request.headers.get('Idempotency-Key');
    req1.flush('indisponivel', { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();
    fixture.detectChanges();

    fixture.componentInstance.submeter();
    const req2 = httpMock.expectOne(SOLICITACOES_URL);
    const key2 = req2.request.headers.get('Idempotency-Key');

    expect(key1).toBeTruthy();
    expect(key2).toBe(key1);
    req2.flush('indisponivel', { status: 503, statusText: 'Service Unavailable' });
  });

  it('lifecycle da key: editar o limite solicitado apos uma tentativa gera uma NOVA Idempotency-Key', async () => {
    const fixture = await montarComAtendimento(120000);
    preencherLimite(fixture, '2000,00');

    fixture.componentInstance.submeter();
    const req1 = httpMock.expectOne(SOLICITACOES_URL);
    const key1 = req1.request.headers.get('Idempotency-Key');
    req1.flush('timeout', { status: 504, statusText: 'Gateway Timeout' });
    await fixture.whenStable();
    fixture.detectChanges();

    preencherLimite(fixture, '1800,00');
    fixture.componentInstance.submeter();
    const req2 = httpMock.expectOne(SOLICITACOES_URL);
    const key2 = req2.request.headers.get('Idempotency-Key');

    expect(key2).not.toBe(key1);
    req2.flush('timeout', { status: 504, statusText: 'Gateway Timeout' });
  });

  it('lifecycle da key: editar observacao ou canal apos uma tentativa tambem gera key nova', async () => {
    const fixture = await montarComAtendimento(120000);
    preencherLimite(fixture, '2000,00');

    fixture.componentInstance.submeter();
    const req1 = httpMock.expectOne(SOLICITACOES_URL);
    const key1 = req1.request.headers.get('Idempotency-Key');
    req1.flush('indisponivel', { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();

    fixture.componentInstance.onObservacaoChange('Cliente pediu por telefone e confirmou depois');
    fixture.componentInstance.submeter();
    const req2 = httpMock.expectOne(SOLICITACOES_URL);
    const key2 = req2.request.headers.get('Idempotency-Key');

    expect(key2).not.toBe(key1);
    req2.flush('indisponivel', { status: 503, statusText: 'Service Unavailable' });
  });

  it('lifecycle da key: 409 LIMITE_VIGENTE_DESATUALIZADO recarrega o atendimento e a proxima tentativa usa key NOVA', async () => {
    const fixture = await montarComAtendimento(120000);
    preencherLimite(fixture, '2000,00');

    fixture.componentInstance.submeter();
    const req1 = httpMock.expectOne(SOLICITACOES_URL);
    const key1 = req1.request.headers.get('Idempotency-Key');
    req1.flush({ codigo: 'LIMITE_VIGENTE_DESATUALIZADO' }, { status: 409, statusText: 'Conflict' });

    const recarga = httpMock.expectOne(ATENDIMENTO_URL);
    recarga.flush({
      cliente: CLIENTE,
      conta: CONTA,
      limiteChequeEspecialVigente: 150000,
      consultadoEm: '2026-09-02T16:05:00Z',
    });
    await fixture.whenStable();
    fixture.detectChanges();

    // O limite vigente visto pela proxima tentativa e o recem-recarregado, nao o antigo.
    expect(fixture.componentInstance.atendimento()?.limiteChequeEspecialVigente).toBe(150000);
    expect(fixture.nativeElement.querySelector('.erro-submissao')?.textContent).toContain('limite mudou');

    fixture.componentInstance.submeter();
    const req2 = httpMock.expectOne(SOLICITACOES_URL);
    const key2 = req2.request.headers.get('Idempotency-Key');

    expect(key2).not.toBe(key1);
    expect(req2.request.body.limiteVigenteVisto).toBe(150000);
    req2.flush('indisponivel', { status: 503, statusText: 'Service Unavailable' });
  });

  it('lifecycle da key: 409 SOLICITACAO_NAO_TERMINAL_EXISTENTE NAO recarrega atendimento e mantem a MESMA key', async () => {
    const fixture = await montarComAtendimento(120000);
    preencherLimite(fixture, '2000,00');

    fixture.componentInstance.submeter();
    const req1 = httpMock.expectOne(SOLICITACOES_URL);
    const key1 = req1.request.headers.get('Idempotency-Key');
    req1.flush({ codigo: 'SOLICITACAO_NAO_TERMINAL_EXISTENTE' }, { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();
    fixture.detectChanges();

    httpMock.expectNone(ATENDIMENTO_URL);
    expect(fixture.nativeElement.querySelector('.erro-submissao')?.textContent).toContain(
      'processo em andamento',
    );

    // O formulario e desabilitado para esta conta (ja existe processo em andamento): submeter()
    // se recusa a reentrar (defesa em profundidade, alem do [disabled] no botao), entao uma nova
    // tentativa manual nem chega a HTTP. Isso prova indiretamente que a key nao foi descartada --
    // e o proprio estado interno confirma que ela permanece a mesma que a tentativa que falhou
    // (adaptando o teste ao desenho do componente, que bloqueia reentrada em vez de permiti-la).
    const botaoSubmit = fixture.nativeElement.querySelector('button[type=submit]') as HTMLButtonElement;
    expect(botaoSubmit.disabled).toBe(true);

    fixture.componentInstance.submeter();
    httpMock.expectNone(SOLICITACOES_URL);

    const chaveInterna = (fixture.componentInstance as unknown as { idempotencyKeyAtual: string | null })
      .idempotencyKeyAtual;
    expect(chaveInterna).toBe(key1);
  });

  it('lifecycle da key: 409 IDEMPOTENCIA_EM_PROCESSAMENTO nao recarrega atendimento e mantem a MESMA key', async () => {
    const fixture = await montarComAtendimento(120000);
    preencherLimite(fixture, '2000,00');

    fixture.componentInstance.submeter();
    const req1 = httpMock.expectOne(SOLICITACOES_URL);
    const key1 = req1.request.headers.get('Idempotency-Key');
    req1.flush({ codigo: 'IDEMPOTENCIA_EM_PROCESSAMENTO' }, { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();
    fixture.detectChanges();

    httpMock.expectNone(ATENDIMENTO_URL);
    expect(fixture.nativeElement.querySelector('.erro-submissao')?.textContent).toContain(
      'ainda esta sendo processada',
    );

    fixture.componentInstance.submeter();
    const req2 = httpMock.expectOne(SOLICITACOES_URL);
    expect(req2.request.headers.get('Idempotency-Key')).toBe(key1);
    req2.flush(respostaAprovada(), { status: 200, statusText: 'OK' });
  });

  it('lifecycle da key: apos desfecho de sucesso, uma nova solicitacao cunha key diferente', async () => {
    const fixture = await montarComAtendimento(120000);
    preencherLimite(fixture, '2000,00');

    fixture.componentInstance.submeter();
    const req1 = httpMock.expectOne(SOLICITACOES_URL);
    const key1 = req1.request.headers.get('Idempotency-Key');
    req1.flush(respostaAprovada(), { status: 201, statusText: 'Created' });
    await fixture.whenStable();
    fixture.detectChanges();

    // Novo atendimento do zero -- o gerente comeca outra manifestacao com os mesmos valores.
    preencherLimite(fixture, '2000,00');
    fixture.componentInstance.submeter();
    const req2 = httpMock.expectOne(SOLICITACOES_URL);
    const key2 = req2.request.headers.get('Idempotency-Key');

    expect(key2).not.toBe(key1);
    req2.flush(respostaAprovada({ solicitacaoId: 'sol-2' }), { status: 201, statusText: 'Created' });
  });

  // --- Validacao local (parsing) -----------------------------------------------------------------

  it('validacao local: limite vazio nao dispara requisicao HTTP', async () => {
    const fixture = await montarComAtendimento(120000);
    preencherLimite(fixture, '');

    fixture.componentInstance.submeter();
    httpMock.expectNone(SOLICITACOES_URL);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.erro-submissao')?.textContent).toContain('valor valido');
  });

  it('validacao local: limite nao numerico nao dispara requisicao HTTP', async () => {
    const fixture = await montarComAtendimento(120000);
    preencherLimite(fixture, 'abc');

    fixture.componentInstance.submeter();
    httpMock.expectNone(SOLICITACOES_URL);
  });
});

describe('parseReaisParaCentavos', () => {
  it('aceita virgula como separador decimal', () => {
    expect(parseReaisParaCentavos('2000,00')).toBe(200000);
  });

  it('aceita valor sem parte fracionaria', () => {
    expect(parseReaisParaCentavos('2000')).toBe(200000);
  });

  it('aceita um unico digito de centavos', () => {
    expect(parseReaisParaCentavos('2000,5')).toBe(200050);
  });

  it('aceita ponto como separador decimal', () => {
    expect(parseReaisParaCentavos('2000.00')).toBe(200000);
  });

  it('entrada vazia ou somente espacos e invalida', () => {
    expect(parseReaisParaCentavos('')).toBeNull();
    expect(parseReaisParaCentavos('   ')).toBeNull();
  });

  it('entrada nao numerica e invalida', () => {
    expect(parseReaisParaCentavos('abc')).toBeNull();
    expect(parseReaisParaCentavos('R$ 200')).toBeNull();
  });

  it('mais de duas casas decimais e invalido', () => {
    expect(parseReaisParaCentavos('12,345')).toBeNull();
  });

  it('nunca produz erro de ponto flutuante em valores tipicos', () => {
    // 0.1 + 0.2 classico: aqui nao ha soma de fracoes -- e a prova de que a conversao nao passa
    // por representacao decimal fracionaria.
    expect(parseReaisParaCentavos('10,10')).toBe(1010);
    expect(parseReaisParaCentavos('19,99')).toBe(1999);
  });
});
