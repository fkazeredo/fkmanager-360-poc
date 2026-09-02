export interface ClienteResumo {
  clienteId: string;
  nome: string;
  cpfMascarado: string;
}

export interface PaginaClientes {
  itens: ClienteResumo[];
  pagina: number;
  tamanho: number;
  totalElementos: number;
  totalPaginas: number;
}

export interface Sessao {
  gerenteId: string;
}
