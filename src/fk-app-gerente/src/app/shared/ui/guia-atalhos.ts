import { Component, ElementRef, effect, inject, viewChild } from '@angular/core';
import { KeyboardService } from '../../core/keyboard';

/**
 * O guia de atalhos de teclado, um `<dialog>` nativo controlado pelo signal `guiaAberta` do
 * KeyboardService. Nativo de proposito: focus trap, Esc e backdrop vem do browser, sem
 * dependencia nova. O evento `close` (Esc, por exemplo) sincroniza o signal de volta.
 */
@Component({
  selector: 'app-guia-atalhos',
  templateUrl: './guia-atalhos.html',
  styleUrl: './guia-atalhos.css',
})
export class GuiaAtalhos {
  protected readonly keyboard = inject(KeyboardService);

  private readonly caixa = viewChild.required<ElementRef<HTMLDialogElement>>('caixa');

  constructor() {
    effect(() => {
      const dialog = this.caixa().nativeElement;
      if (this.keyboard.guiaAberta()) {
        if (!dialog.open) {
          dialog.showModal();
        }
      } else if (dialog.open) {
        dialog.close();
      }
    });
  }

  protected aoFechar(): void {
    this.keyboard.fecharGuia();
  }
}
