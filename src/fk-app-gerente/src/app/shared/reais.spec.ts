import { parseReaisParaCentavos } from './reais';

describe('parseReaisParaCentavos', () => {
  it('aceita virgula como separador decimal', () => {
    expect(parseReaisParaCentavos('2000,00')).toBe(200000);
  });

  it('aceita valor sem parte fracionaria', () => {
    expect(parseReaisParaCentavos('2000')).toBe(200000);
  });

  it('aceita um unico digito de centavos', () => {
    expect(parseReaisParaCentavos('2000,5')).toBe(200050);
  });

  it('aceita ponto como separador decimal', () => {
    expect(parseReaisParaCentavos('2000.00')).toBe(200000);
  });

  it('entrada vazia ou somente espacos e invalida', () => {
    expect(parseReaisParaCentavos('')).toBeNull();
    expect(parseReaisParaCentavos('   ')).toBeNull();
  });

  it('entrada nao numerica e invalida', () => {
    expect(parseReaisParaCentavos('abc')).toBeNull();
    expect(parseReaisParaCentavos('R$ 200')).toBeNull();
  });

  it('mais de duas casas decimais e invalido', () => {
    expect(parseReaisParaCentavos('12,345')).toBeNull();
  });

  it('nunca produz erro de ponto flutuante em valores tipicos', () => {
    // 0.1 + 0.2 classico: aqui nao ha soma de fracoes -- e a prova de que a conversao nao passa
    // por representacao decimal fracionaria.
    expect(parseReaisParaCentavos('10,10')).toBe(1010);
    expect(parseReaisParaCentavos('19,99')).toBe(1999);
  });
});
