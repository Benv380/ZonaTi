package cl.zona_ti.compra_service.Client;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import cl.zona_ti.compra_service.Dto.PerfilBusquedaDto;

// Le pega EN VIVO a auth-service (mismo patron que AsignacionClient) para
// traer los filtros de la empresa (rubro/palabras clave/region) del
// usuario autenticado -- reenvia el mismo header "Authorization" que trajo
// la request original, auth-service identifica la empresa por el JWT,
// nunca se manda un empresaId (evita pedir el perfil de otra empresa).
@Component
public class PerfilClient {

    private final RestClient restClient;

    public PerfilClient(@Value("${auth-service.url}") String baseUrl) {
        // Mismo motivo que AsignacionClient: sin timeout, si auth-service no
        // responde esto se cuelga indefinido y con eso toda la busqueda de
        // compra agil (Puerta 2). CompraAgilService.filtrosDesdePerfil() ya
        // atrapa la excepcion y falla cerrado.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    // Multi-filtro (2026-09-23): devuelve TODOS los filtros de la empresa
    // del usuario, no uno solo.
    public List<PerfilBusquedaDto> misPerfiles(String authorizationHeader) {
        return restClient.get()
                .uri("/auth/perfil/me")
                .header("Authorization", authorizationHeader)
                .retrieve()
                .body(new ParameterizedTypeReference<List<PerfilBusquedaDto>>() {
                });
    }
}
