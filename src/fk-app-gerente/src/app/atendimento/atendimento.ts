import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { AtendimentoService } from '../core/atendimento';
import { Atendimento, ClienteResumo, ContaResumo } from '../core/models';

/**
 * A tela de atendimento: escolhida a ContaCorrente, o gerente ve numa unica tela o Cliente, a
 * conta e o LimiteChequeEspecialVigente que o CoreLegado reconhece agora (AC29, parcial).
 *
 * O valor exibido e sempre o vigente confirmado pelo Core -- nunca um valor local, derivado ou
 * lembrado de antes (ADR-0002).
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
    // Reagir ao input, e nao carregar uma vez no construtor: trocar de Cliente sem recriar o
    // componente precisa recarregar as contas -- caso contrario a tela mostraria a conta de um
    // cliente sob o nome de outro.
    effect(() => {
      const cliente = this.cliente();
      this.carregarContas(cliente.clienteId);
    });
  }

  private carregarContas(clienteId: string): void {
    this.carregandoContas.set(true);
    this.erro.set(null);
    this.contas.set([]);
    this.contaSelecionada.set(null);
    this.atendimento.set(null);

    this.atendimentoService.fetchContas(clienteId).subscribe({
      next: (resultado) => {
        this.contas.set(resultado.itens);
        this.carregandoContas.set(false);
      },
      error: (resposta) => {
        this.carregandoContas.set(false);
        this.erro.set(mensagemDeErro(resposta?.status));
      },
    });
  }

  selecionarConta(conta: ContaResumo): void {
    this.contaSelecionada.set(conta);
    this.atendimento.set(null);
    this.carregandoLimite.set(true);
    this.erro.set(null);

    this.atendimentoService.fetchAtendimento(this.cliente().clienteId, conta.contaId).subscribe({
      next: (resultado) => {
        this.atendimento.set(resultado);
        this.carregandoLimite.set(false);
      },
      error: (resposta) => {
        this.carregandoLimite.set(false);
        this.erro.set(mensagemDeErro(resposta?.status));
      },
    });
  }
}

function formatarReais(centavos: number): string {
  // O backend manda centavos como inteiro (ADR-0005); dividir por 100 aqui, na apresentacao, e o
  // unico ponto do sistema onde o valor vira decimal.
  return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(centavos / 100);
}

/**
 * 502, 503 e 504 renderizam uma unica mensagem de indisponibilidade com acao de repetir, sem
 * expor qual das tres ocorreu -- a distincao permanece em protocolo e diagnostico. O 403 tem
 * mensagem propria porque significa outra coisa: nao e falha, e ausencia de direito.
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
