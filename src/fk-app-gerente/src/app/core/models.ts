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
