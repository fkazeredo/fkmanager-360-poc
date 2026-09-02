package com.fkmanager360.credito.adapter.out.legacy;

import com.fkmanager360.credito.application.port.out.DadosCreditoCorePort;
import com.fkmanager360.credito.application.port.out.InvalidCoreLegadoResponseException;
import com.fkmanager360.credito.domain.ClassificacaoRiscoCreditoBase;
import com.fkmanager360.credito.domain.ContaId;
import com.fkmanager360.credito.domain.DadosCreditoCore;
import com.fkmanager360.credito.domain.LimiteChequeEspecialVigente;
import com.fkmanager360.credito.domain.SituacaoConta;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * ACL propria de Credito sobre o CoreLegado (ADR-0004): a unica classe deste servico que conhece
 * o vocabulario host-centric. Nenhum {@code COD-RET} atravessa esta fronteira (ADR-0005) -- sai
 * como {@link DadosCreditoCore}, como ausencia (conta desconhecida) ou como excecao tipada.
 *
 * <p><b>Sobre {@code consultadoEm} e {@code DAT-ATU-LIM}.</b> O host devolve {@code datAtuLim}:
 * quando <i>ele</i> atualizou o limite. Isso e informacao da fonte sobre a fonte, e nao responde
 * "estes fatos sao de agora?". Quem responde isso e o relogio desta plataforma, no instante em
 * que a captura deu certo -- por isso {@code consultadoEm} vem do {@link Clock} injetado, e
 * nunca de campo algum da resposta. Confundir os dois faria a procedencia mentir: um limite
 * alterado no host em 2024 apareceria como "consultado em 2024", ainda que lido hoje.
 *
 * <p>{@code datAtuLim} e mesmo assim <b>validado</b>, porque e campo obrigatorio do contrato e
 * uma data corrompida e sinal de resposta que a ACL nao pode interpretar. Ele permanece
 * encapsulado aqui: se algum dia virar procedencia congelada, isso sera decisao de quem
 * materializar o ContextoDecisaoCredito, com o campo entrando por escolha e nao por vazamento.
 */
@Component
public class CreditoLegadoAclAdapter implements DadosCreditoCorePort {

    /** Identificacao logica da fonte -- nunca URL, host ou porta (procedencia e de negocio). */
    static final String FONTE = "CoreLegado";

    private static final String COD_RET_SUCESSO = "000";
    private static final String COD_RET_CONTA_NAO_ENCONTRADA = "121";

    private static final String SIT_CTA_REGULAR = "01";
    private static final DateTimeFormatter DATA_HOST = DateTimeFormatter.ofPattern("uuuuMMdd");

    private final RestClient restClient;
    private final Clock clock;

    public CreditoLegadoAclAdapter(@Qualifier("coreLegadoRestClient") RestClient restClient, Clock clock) {
        this.restClient = restClient;
        this.clock = clock;
    }

    @Override
    public Optional<DadosCreditoCore> consultar(ContaId contaId) {
        AclCreditoContaResponse response = CoreLegadoCall.execute(
                () -> restClient.post()
                        .uri("/legado/contas/consulta-credito")
                        .body(new AclCreditoContaRequest(HostFormat.toCodigoHost(contaId.valor())))
                        .retrieve()
                        .body(AclCreditoContaResponse.class),
                "consultar os dados de credito da conta no CoreLegado");

        if (response == null || response.codRet() == null) {
            throw new InvalidCoreLegadoResponseException("CoreLegado devolveu resposta de credito sem COD-RET");
        }

        return switch (response.codRet()) {
            case COD_RET_SUCESSO -> Optional.of(traduzir(response));
            case COD_RET_CONTA_NAO_ENCONTRADA -> Optional.empty();
            default -> throw new InvalidCoreLegadoResponseException(
                    "COD-RET desconhecido do CoreLegado na consulta de credito: " + response.codRet());
        };
    }

    private DadosCreditoCore traduzir(AclCreditoContaResponse response) {
        // Validado, e so isso: o valor nao atravessa a fronteira. Uma data corrompida significa
        // resposta que esta ACL nao sabe interpretar, e vira 502 na borda.
        exigirDataDeAtualizacaoValida(response.datAtuLim());

        return new DadosCreditoCore(
                traduzirLimite(response.vlrLimChqEsp()),
                traduzirSituacao(response.sitCta()),
                traduzirClassificacaoRisco(response.codRscCrd()),
                // O instante da captura vem do relogio desta plataforma -- nunca de datAtuLim.
                clock.instant(),
                FONTE);
    }

    private static LimiteChequeEspecialVigente traduzirLimite(String vlrLimChqEsp) {
        if (vlrLimChqEsp == null || vlrLimChqEsp.isBlank()) {
            // Em branco e ausencia; e limite nao pode ser ausente numa resposta de sucesso.
            throw new InvalidCoreLegadoResponseException("CoreLegado devolveu sucesso sem valor de limite");
        }
        try {
            // Centavos com zero-padding, sem separador: o valor ja e inteiro no host, e continua
            // inteiro aqui -- nenhuma conversao passa por ponto flutuante (ADR-0005).
            return new LimiteChequeEspecialVigente(Long.parseLong(vlrLimChqEsp.trim()));
            // IllegalArgumentException cobre tanto o valor nao numerico (NumberFormatException,
            // que e subclasse dela) quanto o limite negativo recusado pelo proprio value object.
        } catch (IllegalArgumentException e) {
            throw new InvalidCoreLegadoResponseException(
                    "Valor de limite fora do formato esperado: " + vlrLimChqEsp, e);
        }
    }

    private static SituacaoConta traduzirSituacao(String sitCta) {
        if (sitCta == null || sitCta.isBlank()) {
            throw new InvalidCoreLegadoResponseException("CoreLegado devolveu sucesso sem situacao da conta");
        }
        // O host distingue mais estados do que a decisao precisa; o que importa aqui e "regular
        // ou nao". Qualquer codigo diferente do regular e irregular, inclusive um que o host
        // venha a criar depois -- fail-safe na direcao certa: o desconhecido nao vira "regular".
        return SIT_CTA_REGULAR.equals(sitCta.trim()) ? SituacaoConta.REGULAR : SituacaoConta.IRREGULAR;
    }

    private static ClassificacaoRiscoCreditoBase traduzirClassificacaoRisco(String codRscCrd) {
        if (codRscCrd == null || codRscCrd.isBlank()) {
            throw new InvalidCoreLegadoResponseException("CoreLegado devolveu sucesso sem classificacao de risco");
        }
        return switch (codRscCrd.trim()) {
            case "1" -> ClassificacaoRiscoCreditoBase.BAIXO;
            case "2" -> ClassificacaoRiscoCreditoBase.MEDIO;
            case "3" -> ClassificacaoRiscoCreditoBase.ALTO;
            // Diferente da situacao da conta: aqui nao existe "pior caso seguro" -- inventar uma
            // classificacao seria fabricar um insumo de decisao que o host nao deu.
            default -> throw new InvalidCoreLegadoResponseException(
                    "Codigo de classificacao de risco desconhecido: " + codRscCrd);
        };
    }

    private static void exigirDataDeAtualizacaoValida(String datAtuLim) {
        if (datAtuLim == null || datAtuLim.isBlank()) {
            throw new InvalidCoreLegadoResponseException(
                    "CoreLegado devolveu sucesso sem a data de atualizacao do limite");
        }
        try {
            LocalDate.parse(datAtuLim.trim(), DATA_HOST);
        } catch (DateTimeParseException e) {
            throw new InvalidCoreLegadoResponseException(
                    "Data de atualizacao do limite fora do formato yyyyMMdd: " + datAtuLim, e);
        }
    }
}
