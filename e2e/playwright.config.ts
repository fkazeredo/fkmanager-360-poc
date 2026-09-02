import { defineConfig } from '@playwright/test';

/**
 * Harness contra a stack real do Compose (F7) -- sem bypass de seguranca, sem mock de
 * servidor-autorizacao ou servico-carteira-clientes. `docker compose up --build` precisa estar
 * de pe antes de rodar `npm test` (nao subimos a stack aqui: o ciclo de vida do Compose e
 * responsabilidade de quem chama, local ou CI).
 *
 * Certificado TLS e autoassinado (scripts/gerar-segredos-dev.ps1) -- por isso
 * ignoreHTTPSErrors. Isso nao enfraquece a asercao de seguranca: o que estes testes provam e
 * atributos do cookie e ausencia de token, nao a cadeia de confianca do certificado de
 * desenvolvimento.
 */
export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: 'https://localhost',
    ignoreHTTPSErrors: true,
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { browserName: 'chromium' } },
  ],
});
