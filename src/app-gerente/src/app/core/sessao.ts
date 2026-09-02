import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Sessao } from './modelos';

/**
 * O Angular nunca ve um token: so sabe se ha sessao autenticada perguntando ao bff-gerente
 * (ADR-0015). Login e logout sao navegacoes reais do browser -- o fluxo OIDC exige isso, uma
 * chamada AJAX nao substitui o redirect.
 */
@Injectable({ providedIn: 'root' })
export class SessaoService {
  private readonly http = inject(HttpClient);

  readonly gerenteId = signal<string | null>(null);
  readonly carregando = signal(true);

  carregar(): void {
    this.carregando.set(true);
    this.http.get<Sessao>('/bff/api/sessao').subscribe({
      next: (sessao) => {
        this.gerenteId.set(sessao.gerenteId);
        this.carregando.set(false);
      },
      error: () => {
        this.gerenteId.set(null);
        this.carregando.set(false);
      },
    });
  }

  entrar(): void {
    window.location.href = '/bff/oauth2/authorization/servidor-autorizacao';
  }

  sair(): void {
    this.http.post('/bff/logout', null).subscribe({
      next: () => window.location.assign('/'),
      error: () => window.location.assign('/'),
    });
  }
}
