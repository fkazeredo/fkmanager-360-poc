import { SolicitacaoAumentoLimiteResultado } from '../../core/models';

/**
 * Taxonomia de mensagens e de acoes de erro da tela de atendimento. Modulo puro, sem Angular:
 * cada regra "status/codigo -> o que o gerente ve e o que acontece com a Idempotency-Key" e uma
 * funcao exaustivamente testavel sem TestBed; o componente so executa os efeitos.
 */

/**
 * 502, 503 e 504 renderizam uma unica mensagem de indisponibilidade com acao de repetir, sem
 * expor qual das tres ocorreu -- a distincao permanece em protocolo e diagnostico. O 403 e o 404
 * tem mensagem propria porque significam outra coisa: nao sao falha, sao respostas definitivas
 * sobre o pedido.
 */
export function mensagemDeErro(status: number | undefined): string {
  if (status === 403) {
    return 'Voce nao tem direito de atendimento sobre este Cliente.';
  }
  if (status === 404) {
    return 'Conta nao encontrada para este Cliente.';
  }
  return 'Nao foi possivel concluir a operacao agora, tente novamente.';
}

/**
 * O texto exato da decisao (AC29, AC37; spec, secao "Apresentacao"). `FORA_DA_POLITICA_AUTOMATICA`
 * preserva a semantica exata da spec -- nunca afirma risco elevado, problema cadastral ou
 * inelegibilidade permanente. `CONTA_NAO_ELEGIVEL` e `PERFIL_RISCO_INCOMPATIVEL` tem mensagem
 * propria, sem revelar a classificacao de risco bruta ou o status host da conta (a API ja nunca os
 * envia -- aqui e so questao de nao inventar um texto que os exponha).
 */
export function mensagemDecisao(decisao: SolicitacaoAumentoLimiteResultado['decisao']): string {
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
 * {@link acaoParaErroSubmissao}).
 */
export function mensagemDeErroSubmissao(status: number | undefined, codigo: string | undefined): string {
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

/**
 * A acao que um erro de submissao dispara -- o protocolo da Idempotency-Key em forma de dado
 * (plano #0003, "uma manifestacao semantica -> uma key"):
 *
 * - `limiteDesatualizado`: 409 LIMITE_VIGENTE_DESATUALIZADO. A manifestacao acabou (o vigente que
 *   o gerente viu mudou): key nova na proxima tentativa E refetch do atendimento para atualizar o
 *   `limiteVigenteVisto`. Sem reenvio automatico -- o gerente decide.
 * - `bloquearFormulario`: 409 SOLICITACAO_NAO_TERMINAL_EXISTENTE. Ja existe processo nao terminal
 *   para a conta -- nova tentativa e inutil ate ele concluir; a key e preservada (irrelevante com
 *   o formulario bloqueado).
 * - `manterChave`: todos os demais (5xx/504, 409 IDEMPOTENCIA_EM_PROCESSAMENTO, 400/422...). A
 *   manifestacao continua a mesma -- reenvio reusa a MESMA key, e o backend deduplica.
 */
export type AcaoErroSubmissao =
  | { tipo: 'limiteDesatualizado'; mensagem: string }
  | { tipo: 'bloquearFormulario'; mensagem: string }
  | { tipo: 'manterChave'; mensagem: string };

export function acaoParaErroSubmissao(status: number, codigo: string | undefined): AcaoErroSubmissao {
  if (status === 409 && codigo === 'LIMITE_VIGENTE_DESATUALIZADO') {
    return {
      tipo: 'limiteDesatualizado',
      mensagem: 'O limite mudou desde que a tela carregou. O valor vigente foi atualizado -- revise e envie novamente.',
    };
  }
  if (status === 409 && codigo === 'SOLICITACAO_NAO_TERMINAL_EXISTENTE') {
    return { tipo: 'bloquearFormulario', mensagem: mensagemDeErroSubmissao(status, codigo) };
  }
  return { tipo: 'manterChave', mensagem: mensagemDeErroSubmissao(status, codigo) };
}
