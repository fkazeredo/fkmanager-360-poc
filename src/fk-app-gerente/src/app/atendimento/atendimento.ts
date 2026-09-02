import { DatePipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { Subject, of } from 'rxjs';
import { catchError, map, switchMap, tap } from 'rxjs/operators';
import { AtendimentoService } from '../core/atendimento';
import { Atendimento, ClienteResumo, ContaResumo } from '../core/models';

type ResultadoContas = { status: 'sucesso'; itens: ContaResumo[] } | { status: 'erro'; mensagem: string };

type ResultadoAtendimento = { status: 'sucesso'; dados: Atendimento } | { status: 'erro'; mensagem: string };

/**
 * A tela de atendimento: escolhida a ContaCorrente, o gerente ve numa unica tela o Cliente, a
 * conta e o LimiteChequeEspecialVigente que o CoreLegado reconhece agora (AC29, parcial).
 *
 * O valor exibido e sempre o vigente confirmado pelo Core -- nunca um valor local, derivado ou
 * lembrado de antes (ADR-0002).
 *
 * <p>As duas cadeias reativas abaixo (troca de Cliente, selecao de Conta) usam {@code switchMap}
 * deliberadamente, e nao {@code subscribe} direto por selecao: sem cancelamento, uma resposta
 * lenta para uma selecao anterior poderia chegar depois de uma nova selecao e sobrescrever a
 * tela -- o limite autoritativo de uma conta aparecendo sob o rotulo de outra. {@code switchMap}
 * cancela a chamada HTTP anterior (Angular aborta a requisicao subjacente ao desinscrever) assim
 * que uma nova selecao chega, entao a resposta obsoleta nunca e processada.
 */
@Component({
  selector: 'app-atendimento',
  imports: [DatePipe],
  templateUrl: './atendimento.html',
  styleUrl: './atendimento.css',
})
export class AtendimentoComponent {
  private readonly atendimentoService = inject(AtendimentoService);

  readonly cliente = input.required<ClienteResumo>();

  private readonly contaSelecionada$ = new Subject<ContaResumo>();

  readonly contas = signal<ContaResumo[]>([]);
  readonly contaSelecionada = signal<ContaResumo | null>(null);
  readonly atendimento = signal<Atendimento | null>(null);
  readonly carregandoContas = signal(false);
  readonly carregandoLimite = signal(false);
  readonly erro = signal<string | null>(null);

  readonly limiteFormatado = computed(() => {
    const dados = this.atendimento();
    return dados === null ? '' : formatarReais(dados.limiteChequeEspecialVigente);
  });

  constructor() {
    // Reagir ao input via switchMap, e nao um effect() chamando subscribe(): trocar de Cliente
    // enquanto a busca de contas anterior ainda esta em voo precisa cancelar a anterior, nao so
    // reagendar o estado local por cima dela.
    toObservable(this.cliente)
      .pipe(
        tap(() => {
          this.carregandoContas.set(true);
          this.erro.set(null);
          this.contas.set([]);
          this.contaSelecionada.set(null);
          this.atendimento.set(null);
        }),
        switchMap((cliente) => this.buscarContas(cliente.clienteId)),
        takeUntilDestroyed(),
      )
      .subscribe((resultado) => {
        this.carregandoContas.set(false);
        if (resultado.status === 'sucesso') {
          this.contas.set(resultado.itens);
        } else {
          this.erro.set(resultado.mensagem);
        }
      });

    this.contaSelecionada$
      .pipe(
        tap((conta) => {
          this.contaSelecionada.set(conta);
          this.atendimento.set(null);
          this.carregandoLimite.set(true);
          this.erro.set(null);
        }),
        switchMap((conta) => this.buscarAtendimento(conta)),
        takeUntilDestroyed(),
      )
      .subscribe((resultado) => {
        this.carregandoLimite.set(false);
        if (resultado.status === 'sucesso') {
          this.atendimento.set(resultado.dados);
        } else {
          this.erro.set(resultado.mensagem);
        }
      });
  }

  selecionarConta(conta: ContaResumo): void {
    this.contaSelecionada$.next(conta);
  }

  private buscarContas(clienteId: string) {
    return this.atendimentoService.fetchContas(clienteId).pipe(
      map((resultado): ResultadoContas => ({ status: 'sucesso', itens: resultado.itens })),
      catchError((resposta) =>
        of<ResultadoContas>({ status: 'erro', mensagem: mensagemDeErro(resposta?.status) }),
      ),
    );
  }

  private buscarAtendimento(conta: ContaResumo) {
    return this.atendimentoService.fetchAtendimento(this.cliente().clienteId, conta.contaId).pipe(
      map((resultado): ResultadoAtendimento => ({ status: 'sucesso', dados: resultado })),
      catchError((resposta) =>
        of<ResultadoAtendimento>({ status: 'erro', mensagem: mensagemDeErro(resposta?.status) }),
      ),
    );
  }
}

function formatarReais(centavos: number): string {
  // O backend manda centavos como inteiro (ADR-0005); dividir por 100 aqui, na apresentacao, e o
  // unico ponto do sistema onde o valor vira decimal.
  return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(centavos / 100);
}

/**
 * 502, 503 e 504 renderizam uma unica mensagem de indisponibilidade com acao de repetir, sem
 * expor qual das tres ocorreu -- a distincao permanece em protocolo e diagnostico. O 403 e o 404
 * tem mensagem propria porque significam outra coisa: nao sao falha, sao respostas definitivas
 * sobre o pedido.
 */
function mensagemDeErro(status: number | undefined): string {
  if (status === 403) {
    return 'Voce nao tem direito de atendimento sobre este Cliente.';
  }
  if (status === 404) {
    return 'Conta nao encontrada para este Cliente.';
  }
  return 'Nao foi possivel concluir a operacao agora, tente novamente.';
}
