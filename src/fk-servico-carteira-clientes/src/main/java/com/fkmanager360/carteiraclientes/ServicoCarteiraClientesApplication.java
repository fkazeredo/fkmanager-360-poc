package com.fkmanager360.carteiraclientes;

import com.fkmanager360.carteiraclientes.application.port.out.ContasClientePort;
import com.fkmanager360.carteiraclientes.application.port.out.DadosMestresClientePort;
import com.fkmanager360.carteiraclientes.application.port.out.VinculosCarteiraPort;
import com.fkmanager360.carteiraclientes.application.usecase.ConfirmarDireitoDeAtendimento;
import com.fkmanager360.carteiraclientes.application.usecase.ConsultarContextoAtendimento;
import com.fkmanager360.carteiraclientes.application.usecase.ListarClientesDaCarteira;
import com.fkmanager360.carteiraclientes.application.usecase.ListarContasDoCliente;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ServicoCarteiraClientesApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServicoCarteiraClientesApplication.class, args);
    }

    // Os casos de uso sao POJOs: quem os monta e a borda, nao uma anotacao dentro da aplicacao.
    // E o que mantem a camada de aplicacao livre de Spring (ADR-0020) e testavel sem contexto.

    @Bean
    ListarClientesDaCarteira listarClientesDaCarteira(VinculosCarteiraPort vinculos, DadosMestresClientePort dadosMestres) {
        return new ListarClientesDaCarteira(vinculos, dadosMestres);
    }

    @Bean
    ListarContasDoCliente listarContasDoCliente(VinculosCarteiraPort vinculos, ContasClientePort contas) {
        return new ListarContasDoCliente(vinculos, contas);
    }

    @Bean
    ConfirmarDireitoDeAtendimento confirmarDireitoDeAtendimento(VinculosCarteiraPort vinculos, ContasClientePort contas) {
        return new ConfirmarDireitoDeAtendimento(vinculos, contas);
    }

    @Bean
    ConsultarContextoAtendimento consultarContextoAtendimento(
            ConfirmarDireitoDeAtendimento confirmarDireitoDeAtendimento, DadosMestresClientePort dadosMestres) {
        return new ConsultarContextoAtendimento(confirmarDireitoDeAtendimento, dadosMestres);
    }
}
