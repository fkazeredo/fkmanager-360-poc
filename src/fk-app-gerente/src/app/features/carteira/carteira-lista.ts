import { Component, DestroyRef, ElementRef, computed, inject, output, signal } from '@angular/core';
import { KeyboardService } from '../../core/keyboard';
import { ClienteResumo } from '../../core/models';
import { iniciaisDe } from '../../shared/iniciais';
import { CarteiraClientesService } from './carteira-clientes';

@Component({
  selector: 'app-carteira-lista',
  templateUrl: './carteira-lista.html',
  styleUrl: './carteira-lista.css',
})
export class CarteiraLista {
  private readonly carteiraClientes = inject(CarteiraClientesService);
  private readonly keyboard = inject(KeyboardService);
  private readonly host = inject(ElementRef<HTMLElement>);

  /** Selecionar um Cliente e o primeiro passo do atendimento (AC22). */
  readonly clienteSelecionado = output<ClienteResumo>();

  readonly items = signal<ClienteResumo[]>([]);
  readonly selecionado = signal<ClienteResumo | null>(null);
  readonly page = signal(0);
  readonly totalPages = signal(0);
  readonly totalElements = signal(0);
  readonly loading = signal(true);
  readonly error = signal(false);

  /** Filtro local sobre a pagina carregada (nome ou CPF mascarado); a paginacao continua no BFF. */
  readonly filtro = signal('');
  readonly visiveis = computed(() => {
    const termo = this.filtro().trim().toLowerCase();
    if (termo === '') {
      return this.items();
    }
    return this.items().filter(
      (cliente) =>
        cliente.nome.toLowerCase().includes(termo) || cliente.cpfMascarado.includes(termo),
    );
  });

  constructor() {
    this.fetch(0);
    const destroyRef = inject(DestroyRef);
    destroyRef.onDestroy(this.keyboard.registerFocusTarget('carteira', () => this.focarLista()));
    destroyRef.onDestroy(this.keyboard.registerFocusTarget('busca', () => this.focarBusca()));
  }

  fetch(page: number): void {
    // Paginar por teclado troca os itens da lista (e portanto os botoes focados); guardar se o
    // foco estava aqui dentro permite devolve-lo ao primeiro item da pagina nova.
    const focoEstavaNaLista = this.host.nativeElement.contains(document.activeElement);
    this.loading.set(true);
    this.error.set(false);
    this.carteiraClientes.fetchPage(page).subscribe({
      next: (resultado) => {
        this.items.set(resultado.itens);
        this.page.set(resultado.pagina);
        this.totalPages.set(resultado.totalPaginas);
        this.totalElements.set(resultado.totalElementos);
        this.loading.set(false);
        if (focoEstavaNaLista) {
          setTimeout(() => this.focarLista());
        }
      },
      error: () => {
        this.loading.set(false);
        this.error.set(true);
      },
    });
  }

  selecionar(cliente: ClienteResumo): void {
    this.selecionado.set(cliente);
    this.clienteSelecionado.emit(cliente);
  }

  previousPage(): void {
    if (this.page() > 0) {
      this.fetch(this.page() - 1);
    }
  }

  nextPage(): void {
    if (this.page() + 1 < this.totalPages()) {
      this.fetch(this.page() + 1);
    }
  }

  protected iniciais(nome: string): string {
    return iniciaisDe(nome);
  }

  /**
   * Roving tabindex: a lista inteira e UMA parada de Tab (o item selecionado, ou o primeiro);
   * dentro dela navega-se por setas. Vinte clientes nunca viram vinte tab stops.
   */
  protected tabIndexDe(cliente: ClienteResumo, primeiro: boolean): number {
    const selecionado = this.selecionado();
    const paradaDeTab =
      selecionado === null ? primeiro : selecionado.clienteId === cliente.clienteId;
    return paradaDeTab ? 0 : -1;
  }

  protected onListaKeydown(event: KeyboardEvent): void {
    switch (event.key) {
      case 'ArrowDown':
      case 'ArrowUp':
      case 'Home':
      case 'End':
        break;
      case 'ArrowRight':
        event.preventDefault();
        this.nextPage();
        return;
      case 'ArrowLeft':
        event.preventDefault();
        this.previousPage();
        return;
      default:
        return;
    }

    const botoes = this.botoesDaLista();
    if (botoes.length === 0) {
      return;
    }
    const atual = botoes.indexOf(document.activeElement as HTMLButtonElement);
    const proximo =
      event.key === 'ArrowDown'
        ? Math.min(atual + 1, botoes.length - 1)
        : event.key === 'ArrowUp'
          ? Math.max(atual - 1, 0)
          : event.key === 'Home'
            ? 0
            : botoes.length - 1;
    event.preventDefault();
    botoes[proximo].focus();
  }

  /** ArrowDown no campo de busca pula direto para o primeiro resultado da lista. */
  protected onBuscaKeydown(event: KeyboardEvent): void {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.focarLista();
    }
  }

  private focarLista(): void {
    const raiz = this.host.nativeElement as HTMLElement;
    const alvo =
      raiz.querySelector<HTMLButtonElement>('.lista-clientes li.selecionado .selecionar-cliente') ??
      raiz.querySelector<HTMLButtonElement>('.selecionar-cliente');
    alvo?.focus();
  }

  private focarBusca(): void {
    (this.host.nativeElement as HTMLElement).querySelector<HTMLInputElement>('.busca-clientes')?.focus();
  }

  private botoesDaLista(): HTMLButtonElement[] {
    return Array.from(
      (this.host.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>(
        '.selecionar-cliente',
      ),
    );
  }
}
