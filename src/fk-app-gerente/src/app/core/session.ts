import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Session } from './models';

/**
 * O Angular nunca ve um token: so sabe se ha sessao autenticada perguntando ao bff-gerente
 * (ADR-0015). Login e logout sao navegacoes reais do browser -- o fluxo OIDC exige isso, uma
 * chamada AJAX nao substitui o redirect.
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly http = inject(HttpClient);

  readonly gerenteId = signal<string | null>(null);
  readonly loading = signal(true);

  load(): void {
    this.loading.set(true);
    this.http.get<Session>('/bff/api/sessao').subscribe({
      next: (session) => {
        this.gerenteId.set(session.gerenteId);
        this.loading.set(false);
      },
      error: () => {
        this.gerenteId.set(null);
        this.loading.set(false);
      },
    });
  }

  login(): void {
    window.location.href = '/bff/oauth2/authorization/servidor-autorizacao';
  }

  /**
   * O BFF devolve a URL de end-session do servidor-autorizacao (RP-Initiated Logout): navegar
   * ate la encerra tambem a sessao SSO -- sem isso, "Entrar" logava de volta sem pedir senha.
   */
  logout(): void {
    this.http.post<{ redirectUrl?: string }>('/bff/logout', null).subscribe({
      next: (resposta) => window.location.assign(resposta?.redirectUrl ?? '/'),
      error: () => window.location.assign('/'),
    });
  }
}
