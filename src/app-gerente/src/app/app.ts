import { Component, OnInit, inject } from '@angular/core';
import { SessaoService } from './core/sessao';
import { CarteiraLista } from './carteira/carteira-lista';

@Component({
  selector: 'app-root',
  imports: [CarteiraLista],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  protected readonly sessao = inject(SessaoService);

  ngOnInit(): void {
    this.sessao.carregar();
  }
}
