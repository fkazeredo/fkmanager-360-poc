package com.fkmanager360.credito;

import com.fkmanager360.credito.application.port.out.DadosCreditoCorePort;
import com.fkmanager360.credito.application.port.out.DireitoDeAtendimentoPort;
import com.fkmanager360.credito.application.usecase.ConsultarLimiteChequeEspecialVigente;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Contexto Credito, materializado em codigo por #0002 -- e deliberadamente <b>sem persistencia</b>:
 * nao existe ainda estado duravel de Credito, e criar database aqui seria infraestrutura antes da
 * necessidade (ADR-0010, ADR-0014). {@code credito_db} nasce quando a SolicitacaoAumentoLimite
 * nascer.
 */
@SpringBootApplication
public class ServicoCreditoApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServicoCreditoApplication.class, args);
    }

    // O caso de uso e um POJO: quem o monta e a borda, nao uma anotacao dentro da aplicacao.
    @Bean
    ConsultarLimiteChequeEspecialVigente consultarLimiteChequeEspecialVigente(
            DireitoDeAtendimentoPort direitoDeAtendimento, DadosCreditoCorePort dadosCreditoCore) {
        return new ConsultarLimiteChequeEspecialVigente(direitoDeAtendimento, dadosCreditoCore);
    }
}
