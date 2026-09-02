package com.fkmanager360.carteiraclientes;

import com.fkmanager360.carteiraclientes.aplicacao.ListarClientesDaCarteira;
import com.fkmanager360.carteiraclientes.aplicacao.portas.PortaDadosMestresCliente;
import com.fkmanager360.carteiraclientes.aplicacao.portas.PortaVinculosCarteira;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ServicoCarteiraClientesApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServicoCarteiraClientesApplication.class, args);
    }

    @Bean
    ListarClientesDaCarteira listarClientesDaCarteira(PortaVinculosCarteira vinculos, PortaDadosMestresCliente dadosMestres) {
        return new ListarClientesDaCarteira(vinculos, dadosMestres);
    }
}
