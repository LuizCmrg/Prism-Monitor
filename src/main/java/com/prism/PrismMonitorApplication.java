package com.prism;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Ponto de entrada da aplicação PRISM.
 *
 * @EnableScheduling ativa o agendamento da coleta automática.
 * @ConfigurationPropertiesScan registra as propriedades tipadas do prefixo "prism".
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class PrismMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrismMonitorApplication.class, args);
    }
}
