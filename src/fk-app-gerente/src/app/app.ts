import { Component, OnInit, inject } from '@angular/core';
import { SessionService } from './core/session';
import { CarteiraLista } from './carteira/carteira-lista';

@Component({
  selector: 'app-root',
  imports: [CarteiraLista],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  protected readonly session = inject(SessionService);

  ngOnInit(): void {
    this.session.load();
  }
}
