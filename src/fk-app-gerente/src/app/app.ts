import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { KeyboardService } from './core/keyboard';
import { ClienteResumo } from './core/models';
import { SessionService } from './core/session';
import { AtendimentoComponent } from './features/atendimento/atendimento';
import { CarteiraLista } from './features/carteira/carteira-lista';
import { iniciaisDe } from './shared/iniciais';
import { GuiaAtalhos } from './shared/ui/guia-atalhos';

@Component({
  selector: 'app-root',
  imports: [CarteiraLista, AtendimentoComponent, GuiaAtalhos],
  templateUrl: './app.html',
  styleUrl: './app.css',
  host: { '(document:keydown)': 'onDocumentKeydown($event)' },
})
export class App implements OnInit {
  protected readonly session = inject(SessionService);
  protected readonly keyboard = inject(KeyboardService);
  protected readonly clienteEmAtendimento = signal<ClienteResumo | null>(null);

  protected readonly iniciais = computed(() => iniciaisDe(this.session.gerenteId() ?? ''));

  ngOnInit(): void {
    this.session.load();
  }

  protected atender(cliente: ClienteResumo): void {
    this.clienteEmAtendimento.set(cliente);
  }

  protected onDocumentKeydown(event: KeyboardEvent): void {
    this.keyboard.handleKeydown(event);
  }
}
