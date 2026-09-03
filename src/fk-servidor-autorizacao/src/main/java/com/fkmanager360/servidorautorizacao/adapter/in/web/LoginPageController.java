package com.fkmanager360.servidorautorizacao.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * A pagina de login do servidor-autorizacao, no design system da plataforma (ticket #0008) --
 * substitui a pagina gerada pelo Spring Security ("Please sign in"), que era a unica tela sem
 * estilo da jornada. HTML self-contained (CSS inline, sem asset estatico): e uma tela unica, e
 * manter tudo aqui evita configurar resource handlers so para um arquivo.
 *
 * <p>O contrato do formulario e EXATAMENTE o do {@code UsernamePasswordAuthenticationFilter}
 * (POST /login, campos {@code username}/{@code password}, token CSRF em {@code _csrf}) -- os
 * testes e2e dependem dos ids {@code #username}/{@code #password} e do {@code button[type=submit]}.
 *
 * <p>A paleta espelha os tokens de src/fk-app-gerente/src/styles.css (mesmos hex). Duplicacao
 * deliberada e minima: servir a folha do Angular aqui acoplaria o build dos dois deployables.
 */
@Controller
public class LoginPageController {

    @GetMapping(path = "/login", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String paginaDeLogin(HttpServletRequest request) {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        String campoCsrf = csrfToken == null
                ? ""
                : "<input type=\"hidden\" name=\"" + escapeHtml(csrfToken.getParameterName())
                        + "\" value=\"" + escapeHtml(csrfToken.getToken()) + "\">";

        String avisoErro = request.getParameter("error") == null
                ? ""
                : "<p class=\"aviso-erro\" role=\"alert\">Usuario ou senha invalidos. Tente novamente.</p>";

        return TEMPLATE
                .replace("__CSRF__", campoCsrf)
                .replace("__ERRO__", avisoErro);
    }

    private static String escapeHtml(String valor) {
        return valor
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static final String TEMPLATE = """
            <!doctype html>
            <html lang="pt-BR">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta name="theme-color" content="#380616">
              <title>Entrar - fk-manager 360</title>
              <style>
                :root {
                  --marca-100: #fadde4;
                  --marca-600: #a81440;
                  --marca-700: #8c1136;
                  --marca-800: #700e2c;
                  --marca-950: #380616;
                  --tinta-900: #1c1b1f;
                  --tinta-700: #45444b;
                  --tinta-500: #6e6c76;
                  --borda-forte: #d5d0d7;
                  --erro-700: #93201a;
                  --erro-100: #fbeae9;
                }
                * { box-sizing: border-box; }
                body {
                  margin: 0;
                  min-height: 100vh;
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  padding: 1.5rem;
                  background: linear-gradient(135deg, var(--marca-950), var(--marca-800) 60%, var(--marca-700));
                  font-family: 'Inter', 'Segoe UI Variable Text', 'Segoe UI', system-ui, -apple-system, Arial, sans-serif;
                  color: var(--tinta-900);
                  -webkit-font-smoothing: antialiased;
                }
                .cartao {
                  width: 100%;
                  max-width: 24rem;
                  background: #fff;
                  border-radius: 20px;
                  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.35);
                  padding: 2.5rem 2rem;
                  display: flex;
                  flex-direction: column;
                  gap: 1rem;
                }
                .logo {
                  width: 3.5rem;
                  height: 3.5rem;
                  margin: 0 auto;
                  border-radius: 14px;
                  background: var(--marca-600);
                  color: #fff;
                  font-size: 1.375rem;
                  font-weight: 800;
                  letter-spacing: -0.03em;
                  display: flex;
                  align-items: center;
                  justify-content: center;
                }
                h1 { margin: 0; font-size: 1.25rem; text-align: center; letter-spacing: -0.01em; }
                .descricao { margin: 0; text-align: center; font-size: 0.9375rem; color: var(--tinta-500); }
                form { display: flex; flex-direction: column; gap: 0.875rem; margin-top: 0.5rem; }
                label { display: flex; flex-direction: column; gap: 0.375rem; font-size: 0.8125rem; font-weight: 600; color: var(--tinta-700); }
                input {
                  font: inherit;
                  font-size: 1rem;
                  padding: 0.75rem 0.875rem;
                  border: 1px solid var(--borda-forte);
                  border-radius: 10px;
                  transition: border-color 140ms ease, box-shadow 140ms ease;
                }
                input:focus {
                  outline: none;
                  border-color: var(--marca-600);
                  box-shadow: 0 0 0 3px var(--marca-100);
                }
                button {
                  margin-top: 0.5rem;
                  font: inherit;
                  font-weight: 600;
                  color: #fff;
                  background: var(--marca-600);
                  border: none;
                  border-radius: 999px;
                  padding: 0.75rem 1.25rem;
                  cursor: pointer;
                  transition: background 140ms ease;
                }
                button:hover { background: var(--marca-700); }
                button:focus-visible { outline: 2px solid var(--marca-950); outline-offset: 2px; }
                .aviso-erro {
                  margin: 0;
                  padding: 0.75rem 0.875rem;
                  border-radius: 12px;
                  background: var(--erro-100);
                  color: var(--erro-700);
                  font-size: 0.875rem;
                }
                .rodape { margin: 0; text-align: center; font-size: 0.8125rem; color: var(--tinta-500); }
              </style>
            </head>
            <body>
              <main class="cartao">
                <span class="logo" aria-hidden="true">fk</span>
                <h1>fk-manager 360</h1>
                <p class="descricao">Entre com suas credenciais de gerente de relacionamento.</p>
                __ERRO__
                <form method="post" action="/login">
                  __CSRF__
                  <label for="username">Usuario
                    <input type="text" id="username" name="username" autocomplete="username" autofocus required>
                  </label>
                  <label for="password">Senha
                    <input type="password" id="password" name="password" autocomplete="current-password" required>
                  </label>
                  <button type="submit">Entrar</button>
                </form>
                <p class="rodape">Acesso restrito. Sessao protegida por OpenID Connect.</p>
              </main>
            </body>
            </html>
            """;
}
