import { Injectable, signal } from '@angular/core';

/** Regioes da tela que aceitam foco rapido por atalho (tecla -> regiao). */
export type FocusTarget = 'carteira' | 'contas' | 'valor';

/**
 * Atalhos globais de teclado (ticket #0008): a interface e navegavel primariamente por teclado.
 * O App encaminha todo keydown do documento para {@link handleKeydown}; cada feature registra
 * como focar a sua regiao via {@link registerFocusTarget}, entao este servico nunca conhece DOM
 * de componente nenhum.
 *
 * Nenhum atalho dispara enquanto o gerente digita num campo -- a excecao e o proprio dialog do
 * guia, que o browser fecha com Esc por ser um `<dialog>` nativo.
 */
@Injectable({ providedIn: 'root' })
export class KeyboardService {
  /** Estado do guia de atalhos; o dialog em shared/ui/guia-atalhos segue este signal. */
  readonly guiaAberta = signal(false);

  private readonly focusTargets = new Map<FocusTarget, () => void>();

  registerFocusTarget(target: FocusTarget, focar: () => void): () => void {
    this.focusTargets.set(target, focar);
    return () => {
      if (this.focusTargets.get(target) === focar) {
        this.focusTargets.delete(target);
      }
    };
  }

  abrirGuia(): void {
    this.guiaAberta.set(true);
  }

  fecharGuia(): void {
    this.guiaAberta.set(false);
  }

  handleKeydown(event: KeyboardEvent): void {
    if (event.ctrlKey || event.metaKey || event.altKey) {
      return;
    }

    const origem = event.target as HTMLElement | null;
    if (origem?.closest('input, textarea, select, [contenteditable]')) {
      return;
    }

    switch (event.key) {
      case '?':
        this.guiaAberta.update((aberta) => !aberta);
        event.preventDefault();
        break;
      case 'c':
        this.focar('carteira', event);
        break;
      case 'a':
        this.focar('contas', event);
        break;
      case 'l':
        this.focar('valor', event);
        break;
    }
  }

  private focar(target: FocusTarget, event: KeyboardEvent): void {
    const focar = this.focusTargets.get(target);
    if (focar) {
      focar();
      event.preventDefault();
    }
  }
}
