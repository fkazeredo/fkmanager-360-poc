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
});
