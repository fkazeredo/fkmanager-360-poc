import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Atendimento, ContasDoCliente } from './models';

/**
 * O Angular fala apenas com o bff-gerente, nunca com um Resource Server ou com o CoreLegado
 * diretamente. O clienteId viaja no caminho porque a autorizacao de recurso acontece por Cliente,
 * e precisa preceder qualquer leitura no Core (AC23) -- ocultar um botao aqui nunca e o controle.
 */
@Injectable({ providedIn: 'root' })
export class AtendimentoService {
  private readonly http = inject(HttpClient);

  fetchContas(clienteId: string): Observable<ContasDoCliente> {
    return this.http.get<ContasDoCliente>(`/bff/api/clientes/${clienteId}/contas`);
  }

  fetchAtendimento(clienteId: string, contaId: string): Observable<Atendimento> {
    return this.http.get<Atendimento>(`/bff/api/clientes/${clienteId}/contas/${contaId}/atendimento`);
  }
}
