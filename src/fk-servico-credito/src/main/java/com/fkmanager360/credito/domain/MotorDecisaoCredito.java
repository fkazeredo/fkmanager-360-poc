package com.fkmanager360.credito.domain;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Mecanismo automatizado que aplica a PoliticaCredito vigente ao ContextoDecisaoCredito
 * (CONTEXT.md de Credito). O "registry" e um {@link Map} privado, construido no construtor a
 * partir da lista de politicas conhecidas -- sem framework, sem Spring, sem tabela.
 *
 * <p><b>Guardrail critico -- corrige um blocker de review do plano deste ticket:</b>
 * {@link #decidir(ContextoDecisaoCredito, Instant)} resolve a politica pela
 * {@link ContextoDecisaoCredito#versaoPoliticaCredito()} do proprio contexto -- <b>nunca</b> por
 * {@link #versaoVigente()}. {@code versaoVigente()} existe apenas para ser lida UMA VEZ, no
 * instante em que um novo contexto e capturado (isso acontece na camada de aplicacao, nao aqui).
 * Se a versao persistida no contexto nao tiver uma PoliticaCredito correspondente entre as
 * conhecidas, {@link VersaoPoliticaCreditoIndisponivelException} e lancada -- nunca outra politica
 * e usada silenciosamente. E assim que mudar a politica vigente depois de um contexto ja capturado
 * nunca reescreve o significado de uma decisao (retomada inclusive).
 */
public final class MotorDecisaoCredito {

    private final Map<VersaoPoliticaCredito, PoliticaCredito> politicasConhecidas;
    private final VersaoPoliticaCredito versaoVigente;

    public MotorDecisaoCredito(List<PoliticaCredito> conhecidas, VersaoPoliticaCredito vigente) {
        Objects.requireNonNull(conhecidas, "conhecidas e obrigatorio");
        Objects.requireNonNull(vigente, "vigente e obrigatorio");

        Map<VersaoPoliticaCredito, PoliticaCredito> registry = new HashMap<>();
        for (PoliticaCredito politica : conhecidas) {
            registry.put(politica.versao(), politica);
        }
        this.politicasConhecidas = Map.copyOf(registry);
        this.versaoVigente = vigente;

        // Falha na CONSTRUCAO do motor, e nao so na primeira decisao: e o que permite, na etapa de
        // wiring do Spring, recusar o startup da aplicacao se a versao vigente configurada nao tiver
        // implementacao -- nunca deixar uma versao sem PoliticaCredito chegar a ser capturada em TX1.
        validarVersaoVigenteDisponivel();
    }

    public VersaoPoliticaCredito versaoVigente() {
        return versaoVigente;
    }

    public void validarVersaoVigenteDisponivel() {
        if (!politicasConhecidas.containsKey(versaoVigente)) {
            throw new VersaoPoliticaCreditoIndisponivelException(
                    "versaoVigente " + versaoVigente.valor() + " nao possui PoliticaCredito registrada");
        }
    }

    public DecisaoCredito decidir(ContextoDecisaoCredito contexto, Instant decididaEm) {
        Objects.requireNonNull(contexto, "contexto e obrigatorio");
        Objects.requireNonNull(decididaEm, "decididaEm e obrigatorio");

        VersaoPoliticaCredito versaoDoContexto = contexto.versaoPoliticaCredito();
        PoliticaCredito politica = politicasConhecidas.get(versaoDoContexto);
        if (politica == null) {
            throw new VersaoPoliticaCreditoIndisponivelException(
                    "Nenhuma PoliticaCredito registrada para a versao " + versaoDoContexto.valor());
        }

        MotivoDecisaoCredito motivo = politica.avaliar(contexto);
        return new DecisaoCredito(
                motivo.resultado(), motivo, versaoDoContexto, decididaEm, AtorSistema.MOTOR_DECISAO_CREDITO);
    }
}
