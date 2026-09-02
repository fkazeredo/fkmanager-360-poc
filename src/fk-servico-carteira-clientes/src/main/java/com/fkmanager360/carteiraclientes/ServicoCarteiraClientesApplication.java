package com.fkmanager360.carteiraclientes;

import com.fkmanager360.carteiraclientes.application.usecase.ListarClientesDaCarteira;
import com.fkmanager360.carteiraclientes.application.port.out.DadosMestresClientePort;
import com.fkmanager360.carteiraclientes.application.port.out.VinculosCarteiraPort;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ServicoCarteiraClientesApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServicoCarteiraClientesApplication.class, args);
    }

    @Bean
    ListarClientesDaCarteira listarClientesDaCarteira(VinculosCarteiraPort vinculos, DadosMestresClientePort dadosMestres) {
        return new ListarClientesDaCarteira(vinculos, dadosMestres);
    }
}
