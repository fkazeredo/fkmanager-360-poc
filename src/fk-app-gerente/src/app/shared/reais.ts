/**
 * Conversao entre reais (texto/apresentacao) e centavos (inteiro, ADR-0005). Modulo puro, sem
 * Angular -- mesmo padrao de `shared/iniciais.ts`.
 */

export function formatarReais(centavos: number): string {
  // O backend manda centavos como inteiro (ADR-0005); dividir por 100 aqui, na apresentacao, e o
  // unico ponto do sistema onde o valor vira decimal.
  return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(centavos / 100);
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
