import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * As credenciais dos gerentes de demonstracao vivem em AUTH_SERVER_DEMO_USERS, dentro do
 * `.env` gerado localmente por scripts/gerar-segredos-dev.ps1 (gitignored, senha aleatoria por
 * maquina) -- nunca hardcoded aqui. O `.env` e a mesma fonte que o Compose usa para subir
 * servidor-autorizacao, entao o harness sempre autentica contra o usuario que a stack realmente
 * tem configurado.
 */
export interface DemoManagerCredential {
  login: string;
  senha: string;
}

export function readDemoCredentials(): Record<string, DemoManagerCredential> {
  const envPath = resolve(__dirname, '..', '..', '.env');
  let conteudo: string;
  try {
    conteudo = readFileSync(envPath, 'utf-8');
  } catch {
    throw new Error(
      `.env nao encontrado em ${envPath}. Rode scripts/gerar-segredos-dev.ps1 e ` +
        '"docker compose up --build" antes do harness Playwright.',
    );
  }

  const linha = conteudo
    .split(/\r?\n/)
    .find((l) => l.startsWith('AUTH_SERVER_DEMO_USERS='));
  if (!linha) {
    throw new Error('AUTH_SERVER_DEMO_USERS ausente no .env.');
  }

  const valor = linha.slice('AUTH_SERVER_DEMO_USERS='.length);
  const credentials: Record<string, DemoManagerCredential> = {};
  for (const entrada of valor.split(';')) {
    const [login, senha] = entrada.split(':');
    credentials[login] = { login, senha };
  }
  return credentials;
}
