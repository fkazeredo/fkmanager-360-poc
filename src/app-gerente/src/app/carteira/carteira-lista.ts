import { Component, inject, signal } from '@angular/core';
import { CarteiraClientesService } from '../core/carteira-clientes';
import { ClienteResumo } from '../core/modelos';

@Component({
  selector: 'app-carteira-lista',
  templateUrl: './carteira-lista.html',
  styleUrl: './carteira-lista.css',
})
export class CarteiraLista {
  private readonly carteiraClientes = inject(CarteiraClientesService);

  readonly itens = signal<ClienteResumo[]>([]);
  readonly pagina = signal(0);
  readonly totalPaginas = signal(0);
  readonly totalElementos = signal(0);
  readonly carregando = signal(true);
  readonly erro = signal(false);

  constructor() {
    this.buscar(0);
  }

  buscar(pagina: number): void {
    this.carregando.set(true);
    this.erro.set(false);
    this.carteiraClientes.buscarPagina(pagina).subscribe({
      next: (resultado) => {
        this.itens.set(resultado.itens);
        this.pagina.set(resultado.pagina);
        this.totalPaginas.set(resultado.totalPaginas);
        this.totalElementos.set(resultado.totalElementos);
        this.carregando.set(false);
      },
      error: () => {
        this.carregando.set(false);
        this.erro.set(true);
      },
    });
  }

  paginaAnterior(): void {
    if (this.pagina() > 0) {
      this.buscar(this.pagina() - 1);
    }
  }

  proximaPagina(): void {
    if (this.pagina() + 1 < this.totalPaginas()) {
      this.buscar(this.pagina() + 1);
    }
  }
}
