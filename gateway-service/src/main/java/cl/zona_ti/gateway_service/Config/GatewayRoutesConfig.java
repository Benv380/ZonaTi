package cl.zona_ti.gateway_service.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Punto unico de entrada del sistema: reparte cada request al microservicio
 * que corresponde, segun el path. El front (y cualquier otro cliente) le
 * habla siempre a este gateway (puerto 8080), nunca directo a compra-service
 * ni a licitacion-service -- asi se puede mover/escalar/reemplazar cada uno
 * sin que el front se entere, y da un lugar unico donde sumar los proximos
 * microservicios (documento-service, notification-service, etc.).
 *
 * Las URLs downstream salen de application.yml (services.compra-agil.url /
 * services.licitacion.url / services.auth.url), no estan hardcodeadas aca --
 * en Docker Compose cada nombre de servicio se resuelve solo por DNS interno.
 */
@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder,
            @Value("${services.compra-agil.url}") String compraAgilUrl,
            @Value("${services.licitacion.url}") String licitacionUrl,
            @Value("${services.auth.url}") String authUrl) {
        return builder.routes()
                // Compra Agil: listado/detalle/adjuntos (CompraAgilController,
                // AdjuntoController) y el trigger manual de sync
                // (SyncController) -- todo lo que compra-service expone.
                .route("compra-agil", r -> r
                        .path("/compra/agil/**", "/compra/sync/compra-agil")
                        .uri(compraAgilUrl))
                // Licitaciones + sus adjuntos (el scraper Playwright corre
                // adentro de licitacion-service) y su propio trigger manual
                // de sync -- todo lo que licitacion-service expone.
                .route("licitacion", r -> r
                        .path("/compra/licitacion/**", "/compra/sync/licitaciones")
                        .uri(licitacionUrl))
                // Login/emision de JWT -- todo lo que auth-service expone.
                // auth-service no publica puerto propio (ver docker-compose.yml):
                // solo se le puede hablar a traves de este gateway.
                .route("auth", r -> r
                        .path("/auth/**")
                        .uri(authUrl))
                .build();
    }
}
