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

        // La extension de navegador "ZonaTi - Mercado Publico" (envio de
        // cotizaciones, ver Api-Prueba/extension) llama desde
        // "chrome-extension://<id>": se permite SOLO en las rutas que usa
        // (preparar-envio/enviada/documentos de una asignacion, y la descarga
        // de cada documento). Patron y no un id fijo porque el id cambia
        // segun como se cargue la extension. Se registra ANTES de "/**":
        // gana la primera ruta que coincide.
        // auth-service no tiene CORS propio a proposito: el gateway le cambia
        // el Host al reenviar, y con CORS alla las peticiones normales de la
        // pagina se verian como de otro sitio (403 "Invalid CORS request").
        CorsConfiguration extension = new CorsConfiguration();
        extension.addAllowedOrigin("http://localhost:5173");
        extension.addAllowedOriginPattern("chrome-extension://*");
        extension.addAllowedMethod("*");
        extension.addAllowedHeader("*");

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/auth/asignaciones/**", extension);
        source.registerCorsConfiguration("/auth/documentos/**", extension);
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
