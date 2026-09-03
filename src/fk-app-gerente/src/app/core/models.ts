export interface ClienteResumo {
  clienteId: string;
  nome: string;
  cpfMascarado: string;
}

// Nomes de propriedade (itens/pagina/tamanho/totalElementos/totalPaginas) sao o contrato JSON
// publico devolvido por servico-carteira-clientes -- nao mudam so porque o nome do tipo, aqui,
// segue a convencao tecnica em ingles.
export interface ClientesPage {
  itens: ClienteResumo[];
  pagina: number;
  tamanho: number;
  totalElementos: number;
  totalPaginas: number;
}

export interface ContaResumo {
  contaId: string;
  agencia: string;
}

export interface ContasDoCliente {
  itens: ContaResumo[];
}

/**
 * O modelo de apresentacao da tela de atendimento, composto pelo bff-gerente a partir de
 * servico-carteira-clientes e servico-credito (AC30). O limite vem em centavos: a formatacao em
 * reais e daqui, e nenhum texto de interface vem do backend.
 */
export interface Atendimento {
  cliente: ClienteResumo;
  conta: ContaResumo;
  limiteChequeEspecialVigente: number;
  consultadoEm: string;
}

export interface Session {
  gerenteId: string;
}

export type CanalManifestacao = 'PRESENCIAL' | 'TELEFONE' | 'CANAL_DIGITAL';

export interface ManifestacaoCliente {
  canalManifestacao: CanalManifestacao;
  observacao?: string;
}

/**
 * O comando de submissao (spec, secao "Submissao da SolicitacaoAumentoLimite"; plano #0003,
 * secao 9). Valores monetarios em centavos, inteiro (ADR-0005) -- nunca ponto flutuante em
 * nenhuma camada, e por isso o Angular nunca gera este valor com parseFloat.
 */
export interface SolicitacaoAumentoLimiteComando {
  limiteSolicitado: number;
  limiteVigenteVisto: number;
  manifestacaoCliente: ManifestacaoCliente;
}

export interface DecisaoCredito {
  resultado: 'APROVADA' | 'REJEITADA';
  motivo:
    | 'DENTRO_DA_POLITICA_AUTOMATICA'
    | 'CONTA_NAO_ELEGIVEL'
    | 'PERFIL_RISCO_INCOMPATIVEL'
    | 'FORA_DA_POLITICA_AUTOMATICA';
  versaoPoliticaCredito: string;
  decididaEm: string;
}

/**
 * O que fk-servico-credito devolve na submissao, atravessado intacto pelo bff-gerente (proxy puro,
 * sem desserializacao no meio). `limiteSolicitadoPendenteDeEfetivacao` -- presenca E a pendencia
 * (plano #0003): AUSENTE do JSON quando nao ha pendencia (REJEITADA), presente e igual ao
 * limiteSolicitado quando ha (AGUARDANDO_EFETIVACAO). Nunca testar por truthiness -- `0` seria um
 * valor pendente legitimo e um teste de truthiness o trataria como ausente.
 */
export interface SolicitacaoAumentoLimiteResultado {
  solicitacaoId: string;
  contaId: string;
  status: string;
  limiteChequeEspecialVigente: number;
  limiteSolicitado: number;
  limiteSolicitadoPendenteDeEfetivacao?: number;
  decisao: DecisaoCredito;
  registradaEm: string;
}

/**
 * Envelope publico de erro do bff-gerente ({status, codigo}). `codigo` pode estar ausente -- caso
 * de servico-carteira-clientes em 403/404, que nao publica codigo (ver GlobalExceptionHandler do
 * BFF).
 */
export interface EnvelopeErroPublico {
  status: number;
  codigo?: string;
}
