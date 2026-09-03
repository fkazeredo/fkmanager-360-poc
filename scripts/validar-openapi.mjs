#!/usr/bin/env node
// Validacao minima dos contratos OpenAPI versionados em src/<servico>/openapi.yaml (ADR-0019):
// cada arquivo precisa ser YAML sintaticamente valido, ter a forma basica de um documento
// OpenAPI 3.x (openapi/info/paths) e nao ter nenhuma referencia interna ($ref: "#/...") quebrada
// -- o erro mais comum e barato de pegar num contrato editado a mao. Nao substitui teste de
// conformidade contra a implementacao real; e a base sintatica sobre a qual esse teste, quando
// existir, se apoiaria.
//
// O contrato mora dentro do proprio servico (nao num diretorio central) para que uma extracao
// para repositorio proprio (ADR-0011) leve o contrato junto, sem inventario separado.

import { readFileSync, readdirSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import yaml from "js-yaml";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const srcDir = path.resolve(__dirname, "..", "src");

function resolverPonteiro(documento, ponteiro) {
    if (!ponteiro.startsWith("#/")) {
        return true; // referencia externa: fora do escopo desta checagem minima
    }
    const segmentos = ponteiro.slice(2).split("/").map(decodeURIComponent);
    let atual = documento;
    for (const segmento of segmentos) {
        if (atual == null || typeof atual !== "object" || !(segmento in atual)) {
            return false;
        }
        atual = atual[segmento];
    }
    return true;
}

function coletarRefsQuebradas(documento, no, caminho, encontradas) {
    if (Array.isArray(no)) {
        no.forEach((item, indice) => coletarRefsQuebradas(documento, item, `${caminho}[${indice}]`, encontradas));
        return;
    }
    if (no != null && typeof no === "object") {
        for (const [chave, valor] of Object.entries(no)) {
            if (chave === "$ref" && typeof valor === "string") {
                if (!resolverPonteiro(documento, valor)) {
                    encontradas.push(`${caminho}/$ref -> ${valor}`);
                }
                continue;
            }
            coletarRefsQuebradas(documento, valor, `${caminho}/${chave}`, encontradas);
        }
    }
}

function validarArquivo(caminhoCompleto) {
    const erros = [];

    let documento;
    try {
        documento = yaml.load(readFileSync(caminhoCompleto, "utf8"));
    } catch (erroYaml) {
        return [`YAML invalido: ${erroYaml.message}`];
    }

    if (documento == null || typeof documento !== "object") {
        return ["documento vazio ou nao e um objeto"];
    }
    if (typeof documento.openapi !== "string" || !documento.openapi.startsWith("3.")) {
        erros.push(`campo "openapi" ausente ou nao é 3.x (valor: ${documento.openapi})`);
    }
    if (!documento.info || typeof documento.info.title !== "string" || typeof documento.info.version !== "string") {
        erros.push('campo "info.title"/"info.version" ausente');
    }
    if (!documento.paths || typeof documento.paths !== "object" || Object.keys(documento.paths).length === 0) {
        erros.push('campo "paths" ausente ou vazio');
    }

    const refsQuebradas = [];
    coletarRefsQuebradas(documento, documento, "#", refsQuebradas);
    erros.push(...refsQuebradas.map((ref) => `referencia interna quebrada em ${ref}`));

    return erros;
}

const arquivos = readdirSync(srcDir, { withFileTypes: true })
    .filter((entrada) => entrada.isDirectory())
    .map((entrada) => path.join(srcDir, entrada.name, "openapi.yaml"))
    .filter((caminho) => existsSync(caminho));

if (arquivos.length === 0) {
    console.error(`Nenhum contrato encontrado em ${srcDir}/*/openapi.yaml`);
    process.exit(1);
}

let algumFalhou = false;
for (const caminho of arquivos) {
    const rotulo = path.relative(path.resolve(__dirname, ".."), caminho);
    const erros = validarArquivo(caminho);
    if (erros.length === 0) {
        console.log(`OK  ${rotulo}`);
    } else {
        algumFalhou = true;
        console.error(`FALHA  ${rotulo}`);
        erros.forEach((erro) => console.error(`  - ${erro}`));
    }
}

process.exit(algumFalhou ? 1 : 0);
