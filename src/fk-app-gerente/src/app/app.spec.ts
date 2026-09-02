import { provideHttpClient, withFetch } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { App } from './app';

describe('App', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(withFetch()), provideHttpClientTesting()],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('sem sessao autenticada, mostra o botao de entrar e nunca a carteira', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    httpMock.expectOne('/bff/api/sessao').flush('nao autenticado', { status: 401, statusText: 'Unauthorized' });
    await fixture.whenStable();
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.login button')?.textContent).toContain('Entrar');
    expect(compiled.querySelector('app-carteira-lista')).toBeNull();
  });

  it('com sessao autenticada, mostra o gerenteId e a carteira, nunca o botao de entrar', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    httpMock.expectOne('/bff/api/sessao').flush({ gerenteId: 'gerente.a' });
    await fixture.whenStable();
    fixture.detectChanges();

    httpMock.expectOne((req) => req.url === '/bff/api/carteira/clientes')
        .flush({ itens: [], pagina: 0, tamanho: 20, totalElementos: 0, totalPaginas: 0 });
    await fixture.whenStable();
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.usuario')?.textContent).toContain('gerente.a');
    expect(compiled.querySelector('.login')).toBeNull();
  });
});
