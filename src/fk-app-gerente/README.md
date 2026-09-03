# AppGerente

This project was generated using [Angular CLI](https://github.com/angular/angular-cli) version 22.1.6.

## Development server

To start a local development server, run:

```bash
ng serve
```

Once the server is running, open your browser and navigate to `http://localhost:4200/`. The application will automatically reload whenever you modify any of the source files.

> **Nota sobre a porta 4200**: este e o mesmo numero de porta que `compose.yaml` publica para o
> `app-gerente` (nginx, com TLS, em `https://localhost:4200`) desde a decisao do Owner registrada em
> `docs/adr/0013-deployable-e-delimitado-por-perfil-de-execucao.md`. A coincidencia e inofensiva
> porque os dois nunca rodam ao mesmo tempo: ou se sobe a stack integrada via Compose (nginx serve a
> SPA buildada e faz proxy de `/bff/`, `/oauth2/` e `/login`), ou se roda `ng serve` localmente
> contra um `bff-gerente` publicado a parte (usando `proxy.conf.json`, hoje praticamente inerte
> porque o Compose nao publica a porta do `bff-gerente`). Rodar os dois processos ao mesmo tempo
> nesta mesma maquina colidiria na porta 4200 -- nao e um cenario suportado.

## Code scaffolding

Angular CLI includes powerful code scaffolding tools. To generate a new component, run:

```bash
ng generate component component-name
```

For a complete list of available schematics (such as `components`, `directives`, or `pipes`), run:

```bash
ng generate --help
```

## Building

To build the project run:

```bash
ng build
```

This will compile your project and store the build artifacts in the `dist/` directory. By default, the production build optimizes your application for performance and speed.

## Running unit tests

To execute unit tests with the [Vitest](https://vitest.dev/) test runner, use the following command:

```bash
ng test
```

## Running end-to-end tests

For end-to-end (e2e) testing, run:

```bash
ng e2e
```

Angular CLI does not come with an end-to-end testing framework by default. You can choose one that suits your needs.

## Additional Resources

For more information on using the Angular CLI, including detailed command references, visit the [Angular CLI Overview and Command Reference](https://angular.dev/tools/cli) page.
