import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * Leitura direta de `credito_db` pelo mesmo `.env` que o Compose usa (#0005): as asercoes
 * duraveis do fim da jornada 1 (EFETIVADA, protocolo_core, historico sem duplicacao) nao tem
 * superficie HTTP hoje -- acompanhamento e #0007 -- entao a unica forma de observar o estado
 * persistido é consultando o banco, com a credencial de DML da propria aplicacao (somente
 * leitura aqui: SELECT).
 */
const repoRoot = resolve(__dirname, '..', '..');

function readEnvValue(key: string): string {
  const envPath = resolve(repoRoot, '.env');
  const conteudo = readFileSync(envPath, 'utf-8');
  const linha = conteudo.split(/\r?\n/).find((l) => l.startsWith(`${key}=`));
  if (!linha) {
    throw new Error(`${key} ausente no .env.`);
  }
  return linha.slice(key.length + 1);
}

/** Uma unica coluna, uma unica linha -- o suficiente para as asercoes escalares deste harness. */
export function queryCreditoDbScalar(sql: string): string {
  const usuario = readEnvValue('CREDITO_DB_APP_USER');
  const senha = readEnvValue('CREDITO_DB_APP_PASSWORD');
  const banco = readEnvValue('CREDITO_DB_NAME');

  const saida = execFileSync(
    'docker',
    ['compose', 'exec', '-T', '-e', `PGPASSWORD=${senha}`, 'postgres', 'psql', '-U', usuario, '-d', banco, '-tAc', sql],
    { cwd: repoRoot, encoding: 'utf-8' },
  );
  return saida.trim();
}

/**
 * Poll curto sem sleep fixo (mesmo espirito de `expect.poll` no restante do harness): o callback
 * assincrono do simulador confirma primeiro no Core, e so DEPOIS o commit local de EFETIVADA
 * acontece em credito_db -- os dois nao sao atomicos entre si (#0005), entao uma leitura direta
 * de banco, feita logo apos observar o vigente novo na UI, pode legitimamente ainda nao ver o
 * commit local.
 */
export async function pollCreditoDbScalarUntil(
  sql: string,
  esperado: string,
  timeoutMs = 10_000,
): Promise<void> {
  const limite = Date.now() + timeoutMs;
  let ultimoValor = '';
  while (Date.now() < limite) {
    ultimoValor = queryCreditoDbScalar(sql);
    if (ultimoValor === esperado) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  throw new Error(`Esgotado o tempo esperando "${sql}" == "${esperado}" -- ultimo valor observado: "${ultimoValor}"`);
}
