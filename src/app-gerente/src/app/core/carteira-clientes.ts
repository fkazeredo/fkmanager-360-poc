import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { PaginaClientes } from './modelos';

@Injectable({ providedIn: 'root' })
export class CarteiraClientesService {
  private readonly http = inject(HttpClient);

  buscarPagina(pagina: number): Observable<PaginaClientes> {
    return this.http.get<PaginaClientes>('/bff/api/carteira/clientes', {
      params: { pagina },
    });
  }
}
