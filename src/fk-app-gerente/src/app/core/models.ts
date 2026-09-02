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

export interface Session {
  gerenteId: string;
}
