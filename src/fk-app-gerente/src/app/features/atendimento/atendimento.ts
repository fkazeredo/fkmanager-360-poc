import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, ElementRef, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { Subject, of } from 'rxjs';
import { catchError, map, switchMap, tap } from 'rxjs/operators';
import { KeyboardService } from '../../core/keyboard';
import {
  Atendimento,
  CanalManifestacao,
  ClienteResumo,
  ContaResumo,
  EnvelopeErroPublico,
  SolicitacaoAumentoLimiteComando,
  SolicitacaoAumentoLimiteResultado,
} from '../../core/models';
import { iniciaisDe } from '../../shared/iniciais';
import { AtendimentoService } from './atendimento-api';

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
  private readonly keyboard = inject(KeyboardService);
  private readonly host = inject(ElementRef<HTMLElement>);

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

  // --- Submissao da SolicitacaoAumentoLimite (plano #0003, secao 2) --------------------------

  readonly canalManifestacao = signal<CanalManifestacao>('PRESENCIAL');
  readonly observacao = signal('');
  readonly limiteSolicitadoReais = signal(''); // string digitada, NUNCA number com ponto flutuante
  readonly enviando = signal(false);
  readonly resultadoSubmissao = signal<SolicitacaoAumentoLimiteResultado | null>(null);
  readonly erroSubmissao = signal<string | null>(null);
  // Ja existe processo nao terminal para a conta (409 SOLICITACAO_NAO_TERMINAL_EXISTENTE):
  // desabilita nova tentativa ate que a conta mude.
  readonly formularioBloqueado = signal(false);

  /**
   * Estado de controle interno do lifecycle da Idempotency-Key -- deliberadamente NAO e um
   * signal: nao e estado de apresentacao, e um detalhe de protocolo entre tentativas de
   * submissao. GUARDRAIL (plano #0003, secao 2): uma manifestacao semantica -> uma
   * Idempotency-Key. Cunhada na primeira tentativa de uma manifestacao (quando null); reenvios
   * da MESMA manifestacao (retry apos 502/503/504/timeout, ou apos 409
   * IDEMPOTENCIA_EM_PROCESSAMENTO/SOLICITACAO_NAO_TERMINAL_EXISTENTE) reusam a MESMA key porque
   * nada aqui a zera. So vira null -- forcando cunhagem de uma key nova na proxima tentativa --
   * em tres pontos, todos marcados abaixo com "nova manifestacao": os tres handlers de edicao de
   * campo, o fluxo de 409 LIMITE_VIGENTE_DESATUALIZADO, e o desfecho de sucesso (200/201).
   */
  private idempotencyKeyAtual: string | null = null;

  readonly limiteVigenteConfirmadoFormatado = computed(() => {
    const resultado = this.resultadoSubmissao();
    return resultado === null ? '' : formatarReais(resultado.limiteChequeEspecialVigente);
  });

  readonly limitePendenteFormatado = computed(() => {
    const resultado = this.resultadoSubmissao();
    // presenca do campo E a pendencia -- nunca um teste de truthiness, que trataria um pendente
    // de exatos R$ 0,00 como ausente.
    if (resultado === null || resultado.limiteSolicitadoPendenteDeEfetivacao === undefined) {
      return '';
    }
    return formatarReais(resultado.limiteSolicitadoPendenteDeEfetivacao);
  });

  readonly mensagemDecisaoExibida = computed(() => {
    const resultado = this.resultadoSubmissao();
    return resultado === null ? '' : mensagemDecisao(resultado.decisao);
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
          this.resetarFormularioSubmissao();
        }),
        switchMap((cliente) => this.buscarContas(cliente.clienteId)),
        takeUntilDestroyed(),
      )
      .subscribe((resultado) => {
        this.carregandoContas.set(false);
        if (resultado.status === 'sucesso') {
          this.contas.set(resultado.itens);
          // A selecao do Cliente veio de uma acao do gerente; levar o foco para a primeira conta
          // mantem o fluxo inteiro no teclado (setTimeout: depois do render dos botoes).
          setTimeout(() => this.focarContas());
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
          this.resetarFormularioSubmissao();
        }),
        switchMap((conta) => this.buscarAtendimento(conta)),
        takeUntilDestroyed(),
      )
      .subscribe((resultado) => {
        this.carregandoLimite.set(false);
        if (resultado.status === 'sucesso') {
          this.atendimento.set(resultado.dados);
          // Conta escolhida e limite na tela: o proximo passo natural e digitar o valor.
          setTimeout(() => this.focarValor());
        } else {
          this.erro.set(resultado.mensagem);
        }
      });

    const desregistrarContas = this.keyboard.registerFocusTarget('contas', () => this.focarContas());
    const desregistrarValor = this.keyboard.registerFocusTarget('valor', () => this.focarValor());
    const destroyRef = inject(DestroyRef);
    destroyRef.onDestroy(desregistrarContas);
    destroyRef.onDestroy(desregistrarValor);
  }

  protected readonly iniciaisDoCliente = computed(() => iniciaisDe(this.cliente().nome));

  /**
   * Roving tabindex nas contas, como na carteira: a conta selecionada (ou a primeira) e a unica
   * parada de Tab; setas navegam entre as demais.
   */
  protected tabIndexDe(conta: ContaResumo, primeira: boolean): number {
    const selecionada = this.contaSelecionada();
    const paradaDeTab = selecionada === null ? primeira : selecionada.contaId === conta.contaId;
    return paradaDeTab ? 0 : -1;
  }

  protected onContasKeydown(event: KeyboardEvent): void {
    const botoes = Array.from(
      (this.host.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.conta'),
    );
    if (botoes.length === 0) {
      return;
    }
    const atual = botoes.indexOf(document.activeElement as HTMLButtonElement);
    let proximo: number;
    switch (event.key) {
      case 'ArrowRight':
      case 'ArrowDown':
        proximo = Math.min(atual + 1, botoes.length - 1);
        break;
      case 'ArrowLeft':
      case 'ArrowUp':
        proximo = Math.max(atual - 1, 0);
        break;
      case 'Home':
        proximo = 0;
        break;
      case 'End':
        proximo = botoes.length - 1;
        break;
      default:
        return;
    }
    event.preventDefault();
    botoes[proximo].focus();
  }

  private focarContas(): void {
    const raiz = this.host.nativeElement as HTMLElement;
    const alvo =
      raiz.querySelector<HTMLButtonElement>('.conta.selecionada') ??
      raiz.querySelector<HTMLButtonElement>('.conta');
    alvo?.focus();
  }

  private focarValor(): void {
    (this.host.nativeElement as HTMLElement)
      .querySelector<HTMLInputElement>('.limite-solicitado')
      ?.focus();
  }

  selecionarConta(conta: ContaResumo): void {
    this.contaSelecionada$.next(conta);
  }

  // --- Handlers de edicao do formulario -- "nova manifestacao": zeram a Idempotency-Key --------
  //
  // Deliberadamente SEM effect(): marcar isto explicitamente aqui, e nao reagir a mudanca de
  // signal, e o que faz o lifecycle da key ser obvio a partir da leitura do metodo de submissao
  // (que so cunha uma key nova quando idempotencyKeyAtual e null).

  onCanalManifestacaoChange(valor: CanalManifestacao): void {
    this.canalManifestacao.set(valor);
    this.idempotencyKeyAtual = null;
  }

  onObservacaoChange(valor: string): void {
    this.observacao.set(valor);
    this.idempotencyKeyAtual = null;
  }

  onLimiteSolicitadoChange(valor: string): void {
    this.limiteSolicitadoReais.set(valor);
    this.idempotencyKeyAtual = null;
  }

  /**
   * O componente nao importa FormsModule (nenhum outro lugar deste app usa forms do Angular --
   * os campos aqui sao [value]/(input) manuais, como o resto do arquivo). Sem FormsModule,
   * `(ngSubmit)` NAO tem NgForm para interceptar: o `<form>` faz submissao nativa de verdade,
   * recarregando a pagina (bug real encontrado por Playwright -- a tela voltava para a carteira
   * apos "submeter", porque o SPA inteiro reiniciava). `(submit)` e o evento NATIVO do DOM, que
   * qualquer elemento aceita sem modulo nenhum; `preventDefault()` aqui e o que evita a navegacao.
   */
  onFormSubmit(event: Event): void {
    event.preventDefault();
    this.submeter();
  }

  submeter(): void {
    const dados = this.atendimento();
    if (dados === null || this.formularioBloqueado()) {
      return;
    }

    const centavos = parseReaisParaCentavos(this.limiteSolicitadoReais());
    if (centavos === null || centavos <= 0) {
      this.erroSubmissao.set('Informe um valor valido, maior que zero, para o limite solicitado.');
      return;
    }

    const observacaoTrimmed = this.observacao().trim();
    const comando: SolicitacaoAumentoLimiteComando = {
      limiteSolicitado: centavos,
      limiteVigenteVisto: dados.limiteChequeEspecialVigente,
      manifestacaoCliente: {
        canalManifestacao: this.canalManifestacao(),
        ...(observacaoTrimmed === '' ? {} : { observacao: observacaoTrimmed }),
      },
    };

    // Cunha a key SOMENTE quando a tentativa anterior nao deixou uma pendente -- e o unico lugar
    // do componente que gera uma Idempotency-Key nova por iniciativa propria.
    if (this.idempotencyKeyAtual === null) {
      this.idempotencyKeyAtual = crypto.randomUUID();
    }

    this.enviando.set(true);
    this.erroSubmissao.set(null);

    const contaId = dados.conta.contaId;
    const clienteId = this.cliente().clienteId;

    this.atendimentoService
      .submeterSolicitacaoAumentoLimite(clienteId, contaId, comando, this.idempotencyKeyAtual)
      .subscribe({
        next: (resultado) => {
          this.enviando.set(false);
          this.resultadoSubmissao.set(resultado);
          // Desfecho terminal (200/201): a proxima solicitacao e uma manifestacao nova.
          this.idempotencyKeyAtual = null;
        },
        error: (resposta: HttpErrorResponse) => {
          this.enviando.set(false);
          this.tratarErroSubmissao(resposta, clienteId, contaId);
        },
      });
  }

  private tratarErroSubmissao(resposta: HttpErrorResponse, clienteId: string, contaId: string): void {
    const status = resposta.status;
    const erro: EnvelopeErroPublico | null = resposta.error ?? null;
    const codigo: string | undefined = erro?.codigo;

    // 409 LIMITE_VIGENTE_DESATUALIZADO -- e SO quando o codigo e exatamente este (plano #0003).
    // O gerente decide quando reenviar; nao ha reenvio automatico.
    if (status === 409 && codigo === 'LIMITE_VIGENTE_DESATUALIZADO') {
      this.idempotencyKeyAtual = null; // nova manifestacao
      this.erroSubmissao.set(
        'O limite mudou desde que a tela carregou. O valor vigente foi atualizado -- revise e envie novamente.',
      );
      this.atendimentoService.fetchAtendimento(clienteId, contaId).subscribe((dadosAtualizados) => {
        this.atendimento.set(dadosAtualizados);
      });
      return;
    }

    // 409 SOLICITACAO_NAO_TERMINAL_EXISTENTE: nao recarrega limite, nao cunha key nova, bloqueia
    // nova tentativa para esta conta (ja existe processo em andamento).
    if (status === 409 && codigo === 'SOLICITACAO_NAO_TERMINAL_EXISTENTE') {
      this.formularioBloqueado.set(true);
      this.erroSubmissao.set(mensagemDeErroSubmissao(status, codigo));
      return;
    }

    // 409 IDEMPOTENCIA_EM_PROCESSAMENTO: transitorio -- nao recarrega limite, nao cunha key nova,
    // o gerente pode tentar de novo com a MESMA key.
    this.erroSubmissao.set(mensagemDeErroSubmissao(status, codigo));
  }

  private resetarFormularioSubmissao(): void {
    this.canalManifestacao.set('PRESENCIAL');
    this.observacao.set('');
    this.limiteSolicitadoReais.set('');
    this.enviando.set(false);
    this.resultadoSubmissao.set(null);
    this.erroSubmissao.set(null);
    this.formularioBloqueado.set(false);
    this.idempotencyKeyAtual = null;
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

/**
 * Converte o valor em reais digitado pelo gerente para um inteiro de centavos, SEM passar por
 * `parseFloat`/ponto flutuante em nenhum momento (ADR-0005) -- a parte inteira e a fracionaria
 * sao separadas e convertidas como STRINGS, nunca multiplicadas/divididas como fracao decimal.
 *
 * Aceita virgula OU ponto como separador decimal (formulario pt-BR, tolerante a ambos), 0 a 2
 * digitos de fracao. Entrada vazia, so espacos, ou com qualquer caractere fora do padrao
 * `<digitos>[(,|.)<1-2 digitos>]` -- incluindo 3+ digitos de fracao, que descartaria precisao
 * silenciosamente -- devolve `null`: validacao local, antes de qualquer chamada de rede.
 */
export function parseReaisParaCentavos(valorDigitado: string): number | null {
  const valor = valorDigitado.trim();
  if (valor === '') {
    return null;
  }

  const casado = /^(\d+)(?:[.,](\d{1,2}))?$/.exec(valor);
  if (casado === null) {
    return null;
  }

  const parteInteira = casado[1];
  const parteFracionaria = (casado[2] ?? '').padEnd(2, '0');

  const centavos = Number(parteInteira) * 100 + Number(parteFracionaria);
  return Number.isSafeInteger(centavos) ? centavos : null;
}

/**
 * O texto exato da decisao (AC29, AC37; spec, secao "Apresentacao"). `FORA_DA_POLITICA_AUTOMATICA`
 * preserva a semantica exata da spec -- nunca afirma risco elevado, problema cadastral ou
 * inelegibilidade permanente. `CONTA_NAO_ELEGIVEL` e `PERFIL_RISCO_INCOMPATIVEL` tem mensagem
 * propria, sem revelar a classificacao de risco bruta ou o status host da conta (a API ja nunca os
 * envia -- aqui e so questao de nao inventar um texto que os exponha).
 */
function mensagemDecisao(decisao: SolicitacaoAumentoLimiteResultado['decisao']): string {
  if (decisao.resultado === 'APROVADA') {
    return 'Solicitacao aprovada automaticamente.';
  }
  switch (decisao.motivo) {
    case 'CONTA_NAO_ELEGIVEL':
      return 'Esta conta nao esta elegivel para aumento automatico de limite no momento.';
    case 'PERFIL_RISCO_INCOMPATIVEL':
      return 'O perfil desta conta nao e compativel com a concessao automatica de limite.';
    case 'FORA_DA_POLITICA_AUTOMATICA':
      return 'Esta solicitacao nao se enquadra na politica de concessao automatica vigente.';
    default:
      return 'Solicitacao rejeitada.';
  }
}

/**
 * Mensagens da submissao alem das ja cobertas por {@link mensagemDeErro} (403/404/502/503/504,
 * reaproveitadas aqui -- AC37: 502/503/504 permanecem uma unica mensagem, sem revelar qual das
 * tres ocorreu). `400`/`422` (COMANDO_ILEGIVEL, COMANDO_INVALIDO,
 * LIMITE_SOLICITADO_NAO_AUMENTA, IDEMPOTENCIA_FINGERPRINT_DIVERGENTE) nunca deveriam acontecer com
 * um formulario bem-comportado -- mensagem generica, sem tentar explicar cada codigo em prosa.
 * `LIMITE_VIGENTE_DESATUALIZADO` tem fluxo proprio e NAO passa por aqui (ver
 * `tratarErroSubmissao`).
 */
function mensagemDeErroSubmissao(status: number | undefined, codigo: string | undefined): string {
  if (status === 409 && codigo === 'SOLICITACAO_NAO_TERMINAL_EXISTENTE') {
    return 'Ja existe um processo em andamento para esta conta. Aguarde a conclusao antes de solicitar novamente.';
  }
  if (status === 409 && codigo === 'IDEMPOTENCIA_EM_PROCESSAMENTO') {
    return 'Sua solicitacao anterior ainda esta sendo processada. Aguarde um instante.';
  }
  if (status === 400 || status === 422) {
    return 'Nao foi possivel processar esta solicitacao. Tente novamente.';
  }
  return mensagemDeErro(status);
}
