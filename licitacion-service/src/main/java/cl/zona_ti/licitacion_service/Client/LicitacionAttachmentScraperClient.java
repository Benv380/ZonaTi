package cl.zona_ti.licitacion_service.Client;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Le pide los adjuntos de una licitación a scraper-service (servicio Python
 * aparte, con Playwright) en vez de correr el scraping acá mismo -- este
 * servicio (Java) no necesita Chromium instalado, y scraper-service es el
 * único contenedor de todo el sistema que sale por el proxy residencial (ver
 * SCRAPER_PROXY / ts-scraper-proxy en docker-compose.yml), esquivando el
 * bloqueo por IP de datacenter que tiene mercadopublico.cl en ese flujo.
 *
 * Pensado para ser invocado SOLO desde el scheduler en background (ver
 * LicitacionSyncScheduler), nunca directamente desde un request del
 * usuario: scraper-service abre un navegador real (Chromium no-headless),
 * así que es lento y no escala a demanda.
 */
@Component
public class LicitacionAttachmentScraperClient {

    private final RestClient restClient;

    public record AttachmentFile(String nombre, byte[] contenido, int tamanoBytes, String tipoContenido) {}

    // DTOs internos, solo para parsear la respuesta JSON de scraper-service
    // (ver AdjuntosResponse/ArchivoAdjunto en su app.py) -- no se exponen
    // fuera de esta clase.
    private record ArchivoDto(String nombre, String tipoContenido, String contenidoBase64) {}
    private record AdjuntosResponseDto(List<ArchivoDto> archivos) {}

    public LicitacionAttachmentScraperClient(
            @Value("${scraper-service.url}") String baseUrl,
            @Value("${scraper-service.timeout-seconds:120}") long timeoutSeconds) {
        // Timeout de lectura generoso a propósito: scraper-service abre un
        // browser real y navega la ficha + el popup de anexos, puede tardar
        // bastante más que una llamada HTTP común.
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public List<AttachmentFile> descargarAdjuntos(String codigoLicitacion) {
        AdjuntosResponseDto respuesta = restClient.post()
                .uri("/adjuntos/{codigo}", codigoLicitacion)
                .retrieve()
                .body(AdjuntosResponseDto.class);

        if (respuesta == null || respuesta.archivos() == null || respuesta.archivos().isEmpty()) {
            throw new IllegalStateException(
                    "scraper-service no devolvió ningún archivo para " + codigoLicitacion);
        }

        return respuesta.archivos().stream()
                .map(a -> {
                    byte[] bytes = Base64.getDecoder().decode(a.contenidoBase64());
                    return new AttachmentFile(a.nombre(), bytes, bytes.length, a.tipoContenido());
                })
                .toList();
    }
}
