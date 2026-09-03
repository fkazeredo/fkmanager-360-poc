/**
 * Iniciais para avatares: primeira letra da primeira e da ultima parte do nome.
 * "gerente.a" -> "GA", "ANA BEATRIZ SOUZA" -> "AS", "MARIA" -> "M".
 */
export function iniciaisDe(nome: string): string {
  const partes = nome.split(/[^\p{L}\p{N}]+/u).filter((parte) => parte !== '');
  if (partes.length === 0) {
    return '';
  }
  const primeira = partes[0][0];
  const ultima = partes.length > 1 ? partes[partes.length - 1][0] : '';
  return (primeira + ultima).toUpperCase();
}
