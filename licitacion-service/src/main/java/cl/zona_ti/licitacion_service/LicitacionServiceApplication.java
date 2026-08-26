package cl.zona_ti.licitacion_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LicitacionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LicitacionServiceApplication.class, args);
    }
}
