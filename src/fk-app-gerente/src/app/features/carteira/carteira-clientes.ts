import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ClientesPage } from '../../core/models';

@Injectable({ providedIn: 'root' })
export class CarteiraClientesService {
  private readonly http = inject(HttpClient);

  fetchPage(page: number): Observable<ClientesPage> {
    return this.http.get<ClientesPage>('/bff/api/carteira/clientes', {
      params: { pagina: page },
    });
  }
}
