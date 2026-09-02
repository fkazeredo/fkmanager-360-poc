import { Component, OnInit, inject, signal } from '@angular/core';
import { SessionService } from './core/session';
import { CarteiraLista } from './carteira/carteira-lista';
import { AtendimentoComponent } from './atendimento/atendimento';
import { ClienteResumo } from './core/models';

@Component({
  selector: 'app-root',
  imports: [CarteiraLista, AtendimentoComponent],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  protected readonly session = inject(SessionService);
  protected readonly clienteEmAtendimento = signal<ClienteResumo | null>(null);

  ngOnInit(): void {
    this.session.load();
  }

  protected atender(cliente: ClienteResumo): void {
    this.clienteEmAtendimento.set(cliente);
  }
}
