import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Atendimento,
  ContasDoCliente,
  SolicitacaoAumentoLimiteComando,
  SolicitacaoAumentoLimiteResultado,
} from '../../core/models';

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

  /**
   * A fronteira submissao -> decisao automatica (spec, User Story 33; plano #0003, secao 9). O
   * header Idempotency-Key e sempre fornecido por quem chama -- este servico nunca gera, nunca
   * regenera: o lifecycle da key e responsabilidade do componente (uma manifestacao semantica,
   * uma key). CSRF ja e automatico (provideHttpClient(withFetch()) em app.config.ts injeta
   * X-XSRF-TOKEN do cookie XSRF-TOKEN em toda requisicao mutante, sem configuracao extra).
   */
  submeterSolicitacaoAumentoLimite(
    clienteId: string,
    contaId: string,
    comando: SolicitacaoAumentoLimiteComando,
    idempotencyKey: string,
  ): Observable<SolicitacaoAumentoLimiteResultado> {
    return this.http.post<SolicitacaoAumentoLimiteResultado>(
      `/bff/api/clientes/${clienteId}/contas/${contaId}/solicitacoes-aumento-limite`,
      comando,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    );
  }
}
