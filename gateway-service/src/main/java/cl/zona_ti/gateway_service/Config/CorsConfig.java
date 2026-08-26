package cl.zona_ti.gateway_service.Config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * CORS centralizado aca -- reemplaza a los @CrossOrigin sueltos que tenia
 * cada controller en compra-service/licitacion-service. Una vez que el
 * front pase a hablarle solo al gateway (en vez de directo a cada puerto),
 * este es el unico lugar que necesita saber que origen esta permitido.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // Mismo origen que ya usaban los @CrossOrigin de cada controller
        // (Vite en desarrollo local).
        config.addAllowedOrigin("http://localhost:5173");
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
