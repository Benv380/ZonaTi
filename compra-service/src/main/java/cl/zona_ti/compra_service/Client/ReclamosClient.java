package cl.zona_ti.compra_service.Client;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import cl.zona_ti.compra_service.Dto.ReclamosDto.CalculosReclamosResponse;
import cl.zona_ti.compra_service.Service.ReclamosTokenCacheService;

// API interna/no oficial de la ficha publica de comprador de Mercado
// Publico (comprador-api-pro.mercadopublico.cl) -- ver el comentario
// completo en application.yml (mercado-publico.comprador). El JWT
// "anonimo" que exige NO es el mismo que emite servicios-prd.mercadopublico.cl
// (el que usa AuthClient/TokenCacheService para las demas APIs) -- se
// consigue con un scraper Playwright que se renueva solo cada ~6hs (ver
// ReclamosTokenScheduler/ReclamosTokenCacheService), replicando lo que ya
// hace la propia pagina publica de la ficha de un comprador al cargar.
@Component
public class ReclamosClient {

    private final RestClient restClient;
    private final ReclamosTokenCacheService tokenCacheService;

    public ReclamosClient(
            @Value("${mercado-publico.comprador.url}") String baseUrl,
            ReclamosTokenCacheService tokenCacheService) {
        this.tokenCacheService = tokenCacheService;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                // Igual que CompraAgilClient: el envelope {success, errores}
                // viene incluso en 4xx (ej. token vencido = 401, rut no
                // registrado = 400 con success:"NOK") -- no lanzar excepcion
                // por status, se maneja del lado de CompraAgilService.
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                })
                .build();
    }

    // false si todavia no se consiguio ningun token (recien arrancó el
    // servicio, antes del primer ciclo del scheduler) o el que hay ya
    // vencio -- CompraAgilService.reclamosDe() ya sabe fallar cerrado con
    // esto (ver ahi), el resto del panel sigue funcionando igual.
    public boolean estaDisponible() {
        return tokenCacheService.getToken() != null;
    }

    // "rut" es el rut_institucion del organismo (mismo campo que ya
    // tenemos cacheado en CompraAgilEntity) -- esta API lo acepta directo,
    // sin necesitar un codigo interno de organismo.
    public CalculosReclamosResponse calculosReclamos(String rut) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/reclamos/calculos_reclamos")
                        .queryParam("rut", rut)
                        .build())
                .header("Authorization", "Bearer " + tokenCacheService.getToken())
                .retrieve()
                .body(CalculosReclamosResponse.class);
    }
}
