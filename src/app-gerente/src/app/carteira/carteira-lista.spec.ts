import { provideHttpClient, withFetch } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { CarteiraLista } from './carteira-lista';

describe('CarteiraLista', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CarteiraLista],
      providers: [provideHttpClient(withFetch()), provideHttpClientTesting()],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('lista os clientes da primeira pagina ao iniciar', async () => {
    const fixture = TestBed.createComponent(CarteiraLista);
    fixture.detectChanges();

    httpMock.expectOne((req) => req.url === '/bff/api/carteira/clientes' && req.params.get('pagina') === '0')
        .flush({
          itens: [{ clienteId: '1', nome: 'ANA BEATRIZ SOUZA', cpfMascarado: '***.222.333-**' }],
          pagina: 0,
          tamanho: 5,
          totalElementos: 7,
          totalPaginas: 2,
        });
    await fixture.whenStable();
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.nome')?.textContent).toContain('ANA BEATRIZ SOUZA');
    expect(compiled.querySelector('.paginacao')?.textContent).toContain('Pagina 1 de 2');
  });

  it('avancar pagina pede a proxima pagina ao backend', async () => {
    const fixture = TestBed.createComponent(CarteiraLista);
    fixture.detectChanges();

    httpMock.expectOne((req) => req.params.get('pagina') === '0')
        .flush({ itens: [], pagina: 0, tamanho: 5, totalElementos: 7, totalPaginas: 2 });
    await fixture.whenStable();
    fixture.detectChanges();

    fixture.componentInstance.proximaPagina();

    httpMock.expectOne((req) => req.params.get('pagina') === '1')
        .flush({ itens: [], pagina: 1, tamanho: 5, totalElementos: 7, totalPaginas: 2 });
    await fixture.whenStable();
  });

  it('erro do backend mostra mensagem de indisponibilidade, nao a lista vazia', async () => {
    const fixture = TestBed.createComponent(CarteiraLista);
    fixture.detectChanges();

    httpMock.expectOne((req) => req.url === '/bff/api/carteira/clientes')
        .flush('erro', { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.erro')).not.toBeNull();
  });
});
