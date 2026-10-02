package cl.zona_ti.compra_service.Client;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import cl.zona_ti.compra_service.Dto.CompraAgilDto.CompraAgilDetalleResponse;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.CompraAgilListadoResponse;

@Component
public class CompraAgilClient {

    private final RestClient restClient;
    // Cliente aparte, con timeout corto, exclusivo para el chequeo "en
    // vivo" del panel de monitoreo (ver SyncController.salud()) -- ese
    // panel se auto-refresca cada pocos segundos, y esperar hasta 20s por
    // request lo dejaria sintiendose colgado mientras Mercado Publico esta
    // lento/caido. Mismo baseUrl/ticket, timeout mas chico nomas.
    private final RestClient pingClient;

    public CompraAgilClient(
            @Value("${mercado-publico.compra-agil.url}") String baseUrl,
            @Value("${mercado-publico.compra-agil.ticket}") String ticket) {
        // Sin esto, una request colgada del lado de Mercado Publico se
        // queda esperando PARA SIEMPRE (RestClient sin request factory
        // propia no tiene timeout por defecto). Es lo que causaba el
        // "queda en timeout" al abrir el detalle de un item que solo se
        // habia sincronizado por el listado (sin detalle_completo=true en
        // cache): el front corta a los 15s (ver Front/.../lib/api.js),
        // pero el pedido seguia vivo del lado del backend y a veces
        // terminaba bastante despues -- el segundo click ya encontraba el
        // detalle recien cacheado y andaba al toque. Valores generosos
        // (a diferencia de los clientes internos entre microservicios,
        // que usan 3s/5s) porque esto es una API externa de terceros,
        // puede tardar de verdad.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(20));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("ticket", ticket)
                // La API responde el envelope {success, trace, payload, errors} incluso
                // en 4xx/429 (ver seccion 7 de la doc), asi que no debe lanzar excepcion
                // por status.
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                })
                .build();

        SimpleClientHttpRequestFactory pingRequestFactory = new SimpleClientHttpRequestFactory();
        pingRequestFactory.setConnectTimeout(Duration.ofSeconds(3));
        pingRequestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.pingClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(pingRequestFactory)
                .defaultHeader("ticket", ticket)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                })
                .build();
    }

    // filtros admitidos (ver doc API Compra Agil v2, seccion 5.1): ttl_cambio_ms,
    // cambio_desde, cambio_hasta, publicado_desde, publicado_hasta, estado, region,
    // id, q, tamano_pagina, numero_pagina, ordenar_por.
    public CompraAgilListadoResponse listar(Map<String, String> filtros) {
        return restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/v2/compra-agil");
                    filtros.forEach(uriBuilder::queryParam);
                    return uriBuilder.build();
                })
                .retrieve()
                .body(CompraAgilListadoResponse.class);
    }

    public CompraAgilDetalleResponse getDetalleByCodigo(String codigo) {
        return restClient.get()
                .uri("/v2/compra-agil/{codigo}", codigo)
                .retrieve()
                .body(CompraAgilDetalleResponse.class);
    }

    public record EstadoApiEnVivo(boolean disponible, long latenciaMs, String error) {
    }

    // Timeout "duro" del ping -- aparte de connectTimeout/readTimeout del
    // pingClient (3s/5s). Bug real detectado 2026-10-01: el panel de
    // monitoreo tiraba "El servidor no respondió a tiempo (timeout)" en el
    // FRONT (su propio limite de 15s, ver lib/api.js) pese a que connect+
    // read del pingClient suman ~8s como mucho -- algo en el camino (DNS,
    // red) estaba dejando la llamada colgada mas de lo que esos timeouts
    // deberian permitir. Correrlo en un hilo aparte con Future.get(limite)
    // garantiza el corte SIN depender de que el timeout HTTP configurado
    // se respete de verdad.
    private static final long PING_TIMEOUT_DURO_SEGUNDOS = 8;
    private final ExecutorService pingExecutor = Executors.newFixedThreadPool(2);

    // Chequeo liviano ("¿responde Mercado Publico ahora mismo?") para el
    // panel de monitoreo -- ver SyncController.salud(). Pide 1 sola fila
    // del listado (lo mas barato que se puede pedir) con el pingClient de
    // timeout corto, asi el panel no se siente colgado si la API esta
    // lenta/caida.
    public EstadoApiEnVivo pingVivo() {
        long inicio = System.currentTimeMillis();
        CompletableFuture<EstadoApiEnVivo> future = CompletableFuture.supplyAsync(() -> {
            try {
                // toEntity() (no solo .body()) para tener el status HTTP a
                // mano -- antes, cuando la respuesta no calzaba con el
                // envelope esperado (ej: una pagina de bloqueo por cuota
                // diaria agotada, sin "success"/"errors"), el panel
                // mostraba "success=null" sin explicar nada (bug real
                // 2026-10-02: la cuota diaria se agoto de verdad). El
                // status (ej. 429) ya es mucho mas util para diagnosticar.
                ResponseEntity<CompraAgilListadoResponse> respuesta = pingClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/v2/compra-agil").queryParam("tamano_pagina", "1").build())
                        .retrieve()
                        .toEntity(CompraAgilListadoResponse.class);
                long latencia = System.currentTimeMillis() - inicio;

                CompraAgilListadoResponse parseado = respuesta.getBody();
                boolean ok = respuesta.getStatusCode().is2xxSuccessful()
                        && parseado != null && "OK".equalsIgnoreCase(parseado.success());
                String detalle = ok ? null
                        : describirRespuestaInesperada(respuesta.getStatusCode().value(), parseado);
                return new EstadoApiEnVivo(ok, latencia, detalle);
            } catch (Exception e) {
                return new EstadoApiEnVivo(false, System.currentTimeMillis() - inicio, e.getMessage());
            }
        }, pingExecutor);

        try {
            return future.get(PING_TIMEOUT_DURO_SEGUNDOS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // El hilo puede seguir vivo de fondo (un read bloqueado no
            // siempre responde a la interrupcion) -- no importa, el
            // pingExecutor tiene mas de un hilo y este se libera solo en
            // cuanto el pingClient corte por su propio timeout.
            return new EstadoApiEnVivo(false, PING_TIMEOUT_DURO_SEGUNDOS * 1000,
                    "Sin respuesta en " + PING_TIMEOUT_DURO_SEGUNDOS + "s (timeout duro)");
        } catch (Exception e) {
            return new EstadoApiEnVivo(false, System.currentTimeMillis() - inicio, e.getMessage());
        }
    }

    // El body puede llegar 200 OK igual con success="false" (ver comentario
    // sobre defaultStatusHandler en el constructor) -- sin esto, el panel
    // de monitoreo solo mostraba "Respuesta inesperada de Mercado Público"
    // sin decir que devolvió realmente, nada util para diagnosticar.
    // "status" (2026-10-02): cuando "parseado" no calza con el envelope
    // esperado (ej. una pagina de bloqueo por cuota agotada, sin "success"/
    // "errors" -- Jackson igual arma el record, solo que con todo null),
    // antes esto se veia como "success=null" sin explicar nada -- el
    // status HTTP (ej. 429) ya ayuda bastante mas a diagnosticar.
    private static String describirRespuestaInesperada(int status, CompraAgilListadoResponse parseado) {
        if (parseado != null && parseado.errors() != null && !parseado.errors().isEmpty()) {
            return parseado.errors().stream()
                    .map(err -> err.mensaje() != null ? err.mensaje() : err.codigo())
                    .filter(m -> m != null && !m.isBlank())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("HTTP " + status + ", success=" + parseado.success());
        }
        return "HTTP " + status + ", success=" + (parseado != null ? parseado.success() : null);
    }

}
