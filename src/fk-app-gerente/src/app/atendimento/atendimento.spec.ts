import { provideHttpClient, withFetch } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AtendimentoComponent } from './atendimento';
import { ClienteResumo } from '../core/models';

const CLIENTE: ClienteResumo = {
  clienteId: '1',
  nome: 'ANA BEATRIZ SOUZA',
  cpfMascarado: '***.222.333-**',
};

const CLIENTE_B: ClienteResumo = {
  clienteId: '2',
  nome: 'CARLOS EDUARDO LIMA',
  cpfMascarado: '***.333.444-**',
};

describe('AtendimentoComponent', () => {
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

  async function montarComContas(contas: Array<{ contaId: string; agencia: string }>) {
    const fixture = TestBed.createComponent(AtendimentoComponent);
    fixture.componentRef.setInput('cliente', CLIENTE);
    fixture.detectChanges();

    httpMock.expectOne('/bff/api/clientes/1/contas').flush({ itens: contas });
    await fixture.whenStable();
    fixture.detectChanges();

    return fixture;
  }

  it('lista as contas do cliente selecionado', async () => {
    const fixture = await montarComContas([
      { contaId: '10001', agencia: '0001' },
      { contaId: '10002', agencia: '0001' },
    ]);

    const contas = (fixture.nativeElement as HTMLElement).querySelectorAll('.lista-contas .conta');
    expect(contas.length).toBe(2);
    expect(contas[0].textContent).toContain('10001');
  });

  it('selecionar a conta mostra o limite vigente formatado em reais', async () => {
    const fixture = await montarComContas([{ contaId: '10001', agencia: '0001' }]);

    fixture.componentInstance.selecionarConta({ contaId: '10001', agencia: '0001' });

    httpMock.expectOne('/bff/api/clientes/1/contas/10001/atendimento').flush({
      cliente: CLIENTE,
      conta: { contaId: '10001', agencia: '0001' },
      // Centavos, como o backend manda (ADR-0005).
      limiteChequeEspecialVigente: 500000,
      consultadoEm: '2026-09-02T16:00:00Z',
    });
    await fixture.whenStable();
    fixture.detectChanges();

    const valor = (fixture.nativeElement as HTMLElement).querySelector('.limite-vigente .valor');
    // A formatacao em reais e da apresentacao; o backend nunca manda texto formatado.
    expect(valor?.textContent?.replace(/ /g, ' ')).toContain('5.000,00');
  });

  it('403 diz que falta direito de atendimento, e nao que o sistema falhou', async () => {
    const fixture = TestBed.createComponent(AtendimentoComponent);
    fixture.componentRef.setInput('cliente', CLIENTE);
    fixture.detectChanges();

    httpMock
      .expectOne('/bff/api/clientes/1/contas')
      .flush('sem direito', { status: 403, statusText: 'Forbidden' });
    await fixture.whenStable();
    fixture.detectChanges();

    const erro = (fixture.nativeElement as HTMLElement).querySelector('.erro');
    expect(erro?.textContent).toContain('direito de atendimento');
  });

  it('indisponibilidade renderiza uma unica mensagem, sem dizer qual das tres ocorreu', async () => {
    const fixture = await montarComContas([{ contaId: '10001', agencia: '0001' }]);

    fixture.componentInstance.selecionarConta({ contaId: '10001', agencia: '0001' });

    httpMock
      .expectOne('/bff/api/clientes/1/contas/10001/atendimento')
      .flush('indisponivel', { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();
    fixture.detectChanges();

    const erro = (fixture.nativeElement as HTMLElement).querySelector('.erro');
    expect(erro?.textContent).toContain('tente novamente');
    expect(erro?.textContent).not.toContain('503');
    expect(erro?.textContent).not.toContain('timeout');
  });

  it('cliente sem conta e informado como tal, nao como erro', async () => {
    const fixture = await montarComContas([]);

    const texto = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(texto).toContain('nao possui conta corrente');
    expect((fixture.nativeElement as HTMLElement).querySelector('.erro')).toBeNull();
  });

  it('404 diz que a conta nao foi encontrada, nao a mensagem generica de indisponibilidade', async () => {
    const fixture = await montarComContas([{ contaId: '10001', agencia: '0001' }]);

    fixture.componentInstance.selecionarConta({ contaId: '10001', agencia: '0001' });

    httpMock
      .expectOne('/bff/api/clientes/1/contas/10001/atendimento')
      .flush('nao encontrada', { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();
    fixture.detectChanges();

    const erro = (fixture.nativeElement as HTMLElement).querySelector('.erro');
    expect(erro?.textContent).toContain('nao encontrada');
  });

  // --- B1: corrida entre selecoes -----------------------------------------------------------
  //
  // O cenario adversarial do review: uma resposta lenta para a selecao ANTERIOR chega DEPOIS da
  // resposta para a selecao ATUAL. Sem switchMap, a tela mostraria o limite autoritativo de uma
  // conta sob o rotulo de outra. O switchMap cancela a inscricao da chamada anterior assim que a
  // nova selecao chega -- entao mesmo quando o teste ainda flush() a requisicao antiga (o
  // servidor de teste nao sabe que o cliente ja descartou o interesse nela), o componente nunca
  // processa essa emissao.

  it('troca rapida de conta: a resposta da conta anterior, chegando depois, nunca sobrescreve a selecao atual', async () => {
    const fixture = await montarComContas([
      { contaId: '10001', agencia: '0001' },
      { contaId: '10002', agencia: '0001' },
    ]);

    // 1. selecionar conta A
    fixture.componentInstance.selecionarConta({ contaId: '10001', agencia: '0001' });
    const requisicaoA = httpMock.expectOne('/bff/api/clientes/1/contas/10001/atendimento');

    // 2. request A permanece pendente; 3. selecionar conta B
    fixture.componentInstance.selecionarConta({ contaId: '10002', agencia: '0001' });
    const requisicaoB = httpMock.expectOne('/bff/api/clientes/1/contas/10002/atendimento');

    // 4. resposta B chega
    requisicaoB.flush({
      cliente: CLIENTE,
      conta: { contaId: '10002', agencia: '0001' },
      limiteChequeEspecialVigente: 120000,
      consultadoEm: '2026-09-02T16:00:00Z',
    });
    await fixture.whenStable();
    fixture.detectChanges();

    // 5. resposta A chega depois (fora de ordem)
    // switchMap deveria ter cancelado a chamada da selecao anterior.
    expect(requisicaoA.cancelled).toBe(true);
    if (!requisicaoA.cancelled) {
      requisicaoA.flush({
        cliente: CLIENTE,
        conta: { contaId: '10001', agencia: '0001' },
        limiteChequeEspecialVigente: 500000,
        consultadoEm: '2026-09-02T16:00:00Z',
      });
    }
    await fixture.whenStable();
    fixture.detectChanges();

    // 6. UI continua mostrando exclusivamente B
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.limite-vigente .valor')?.textContent).toContain('1.200,00');
    expect(el.querySelector('.limite-vigente .valor')?.textContent).not.toContain('5.000,00');
    expect(el.querySelector('.conta.selecionada')?.textContent).toContain('10002');
  });

  it('troca rapida de cliente: as contas do cliente anterior, chegando depois, nunca aparecem na tela do cliente atual', async () => {
    const fixture = TestBed.createComponent(AtendimentoComponent);
    fixture.componentRef.setInput('cliente', CLIENTE);
    fixture.detectChanges();

    const requisicaoContasA = httpMock.expectOne('/bff/api/clientes/1/contas');

    fixture.componentRef.setInput('cliente', CLIENTE_B);
    fixture.detectChanges();

    const requisicaoContasB = httpMock.expectOne('/bff/api/clientes/2/contas');

    requisicaoContasB.flush({ itens: [{ contaId: '20001', agencia: '0002' }] });
    await fixture.whenStable();
    fixture.detectChanges();

    // switchMap deveria ter cancelado a busca de contas do cliente anterior.
    expect(requisicaoContasA.cancelled).toBe(true);
    if (!requisicaoContasA.cancelled) {
      requisicaoContasA.flush({ itens: [{ contaId: '10001', agencia: '0001' }] });
    }
    await fixture.whenStable();
    fixture.detectChanges();

    const contas = (fixture.nativeElement as HTMLElement).querySelectorAll('.lista-contas .conta');
    expect(contas.length).toBe(1);
    expect(contas[0].textContent).toContain('20001');
    expect((fixture.nativeElement as HTMLElement).querySelector('.cliente-nome')?.textContent).toContain(
      'CARLOS EDUARDO LIMA',
    );
  });

  it('destruir o componente com uma requisicao em voo cancela a inscricao (takeUntilDestroyed)', async () => {
    const fixture = await montarComContas([{ contaId: '10001', agencia: '0001' }]);

    fixture.componentInstance.selecionarConta({ contaId: '10001', agencia: '0001' });
    const requisicaoEmVoo = httpMock.expectOne('/bff/api/clientes/1/contas/10001/atendimento');

    fixture.destroy();

    // takeUntilDestroyed() cancela a inscricao no destroy: a propria infraestrutura de teste do
    // Angular recusa flush() num pedido cancelado ("Cannot flush a cancelled request"), que e a
    // prova de que nenhuma escrita tardia em signal pode acontecer apos o componente morrer.
    expect(requisicaoEmVoo.cancelled).toBe(true);
  });
});
