package cl.zona_ti.compra_service.Client;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// Le pega EN VIVO a auth-service (GET /auth/internal/asignaciones/codigos)
// -- a diferencia de PerfilClient, esto lo llama LimpiezaScheduler en
// background, SIN ningun usuario logueado de por medio (no hay JWT que
// reenviar). Se autentica con un secreto compartido (header X-Internal-Key,
// ver internal.service-key/InternalController en auth-service) en vez del
// Authorization normal.
@Component
public class AsignacionInternoClient {

    private final RestClient restClient;
    private final String claveInterna;

    public AsignacionInternoClient(
            @Value("${auth-service.url}") String baseUrl,
            @Value("${internal.service-key}") String claveInterna) {
        this.claveInterna = claveInterna;

        // Mismo timeout que PerfilClient (3s/5s) -- si auth-service no
        // responde, LimpiezaScheduler debe fallar RAPIDO y abortar la
        // limpieza de ese ciclo (ver comentario en LimpiezaScheduler: mejor
        // no borrar nada que borrar de mas por asumir "sin asignaciones"
        // cuando en realidad no se pudo confirmar).
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    // "tipo": "COMPRA_AGIL" o "LICITACION" (ver TipoAsignacion en
    // auth-service). Devuelve TODOS los codigos con al menos una asignacion
    // en TODO el sistema (cualquier empresa) -- deja que cualquier
    // excepcion (timeout, 4xx/5xx) se propague, a proposito: quien llama
    // decide que hacer, no se traga el error acá para no esconder un
    // "no se pudo confirmar" detras de una lista vacia.
    public List<String> codigosConAsignacion(String tipo) {
        return restClient.get()
                .uri(b -> b.path("/auth/internal/asignaciones/codigos").queryParam("tipo", tipo).build())
                .header("X-Internal-Key", claveInterna)
                .retrieve()
                .body(new ParameterizedTypeReference<List<String>>() {
                });
    }
}
