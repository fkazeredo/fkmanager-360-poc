import { Component, inject, signal } from '@angular/core';
import { CarteiraClientesService } from '../core/carteira-clientes';
import { ClienteResumo } from '../core/models';

@Component({
  selector: 'app-carteira-lista',
  templateUrl: './carteira-lista.html',
  styleUrl: './carteira-lista.css',
})
export class CarteiraLista {
  private readonly carteiraClientes = inject(CarteiraClientesService);

  readonly items = signal<ClienteResumo[]>([]);
  readonly page = signal(0);
  readonly totalPages = signal(0);
  readonly totalElements = signal(0);
  readonly loading = signal(true);
  readonly error = signal(false);

  constructor() {
    this.fetch(0);
  }

  fetch(page: number): void {
    this.loading.set(true);
    this.error.set(false);
    this.carteiraClientes.fetchPage(page).subscribe({
      next: (resultado) => {
        this.items.set(resultado.itens);
        this.page.set(resultado.pagina);
        this.totalPages.set(resultado.totalPaginas);
        this.totalElements.set(resultado.totalElementos);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.error.set(true);
      },
    });
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
}
