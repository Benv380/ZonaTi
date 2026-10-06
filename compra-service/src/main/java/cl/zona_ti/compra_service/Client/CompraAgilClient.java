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
import cl.zona_ti.compra_service.Util.JsonTolerante;
import tools.jackson.databind.ObjectMapper;

@Component
public class CompraAgilClient {

    // Listado y detalle (los usa el scheduler en background, y el listado
    // tambien la busqueda en vivo de GLOBAL) -- ver timeouts en el
    // constructor.
    private final RestClient syncClient;
    private final ObjectMapper jsonTolerante = JsonTolerante.crear();
    // Cliente aparte, con timeout corto, exclusivo para el chequeo "en
    // vivo" del panel de monitoreo (ver SyncController.salud()) -- ese
    // panel se auto-refresca cada pocos segundos, y esperar hasta 20s por
    // request lo dejaria sintiendose colgado mientras Mercado Publico esta
    // lento/caido. Mismo baseUrl/ticket, timeout mas chico nomas.
    private final RestClient pingClient;

    public CompraAgilClient(
            @Value("${mercado-publico.compra-agil.url}") String baseUrl,
            @Value("${mercado-publico.compra-agil.ticket}") String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw new IllegalStateException("Falta MERCADO_PUBLICO_TICKET (ticket de la API de Mercado Publico) en el entorno.");
        }
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
        //
        // Lectura de 45s (antes 20s): visto en vivo 2026-10-06, el detalle
        // de una compra tardaba mas de 20s en responder ("Read timed out")
        // en varias compras seguidas, y el listado ahora pide paginas de 50.
        // Corre en background (scheduler), no hay un usuario esperando.
        SimpleClientHttpRequestFactory syncRequestFactory = new SimpleClientHttpRequestFactory();
        syncRequestFactory.setConnectTimeout(Duration.ofSeconds(5));
        syncRequestFactory.setReadTimeout(Duration.ofSeconds(45));
        this.syncClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(syncRequestFactory)
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
        return listarConEstado(filtros).cuerpo();
    }

    // Respuesta del listado + status HTTP + texto crudo. Bug real
    // 2026-10-06: el sync fallaba con "success=null" -- la respuesta no traia
    // el envelope documentado {success, payload, errors} (cortes del gateway
    // delante de la API a los ~29s), y sin status ni body no habia forma de
    // saber que habia respondido. "cuerpo" queda null si el body no se puede
    // leer como el envelope.
    public record RespuestaListado(int status, CompraAgilListadoResponse cuerpo, String crudo) {

        public boolean esOk() {
            return cuerpo != null && "OK".equalsIgnoreCase(cuerpo.success());
        }

        // "HTTP 504: {"message":"Endpoint request timed out"}" o, si es el
        // envelope de la API, sus errores.
        public String describir() {
            if (cuerpo != null && cuerpo.success() != null) {
                String errores = cuerpo.errors() == null ? "" : cuerpo.errors().stream()
                        .map(err -> (err.codigo() != null ? err.codigo() + " " : "") + (err.mensaje() != null ? err.mensaje() : ""))
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("");
                return "HTTP " + status + ", success=" + cuerpo.success() + (errores.isBlank() ? "" : ": " + errores);
            }
            return "HTTP " + status + ": " + recortar(crudo);
        }
    }

    public RespuestaListado listarConEstado(Map<String, String> filtros) {
        ResponseEntity<String> respuesta = syncClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/v2/compra-agil");
                    filtros.forEach(uriBuilder::queryParam);
                    return uriBuilder.build();
                })
                .retrieve()
                .toEntity(String.class);
        String crudo = respuesta.getBody();
        CompraAgilListadoResponse cuerpo = null;
        if (crudo != null && !crudo.isBlank()) {
            try {
                cuerpo = jsonTolerante.readValue(crudo, CompraAgilListadoResponse.class);
            } catch (Exception e) {
                cuerpo = null;
            }
        }
        return new RespuestaListado(respuesta.getStatusCode().value(), cuerpo, crudo);
    }

    private static String recortar(String texto) {
        if (texto == null || texto.isBlank()) {
            return "(body vacio)";
        }
        String limpio = texto.replaceAll("\\s+", " ").trim();
        return limpio.length() <= 300 ? limpio : limpio.substring(0, 300) + "...";
    }

    // Se lee como texto y se parsea con JsonTolerante (no con el conversor
    // por defecto): bug real 2026-10-06, algunas compras traen en el detalle
    // un campo con un tipo distinto al documentado y el conversor estricto
    // descartaba el detalle COMPLETO ("Error while extracting response for
    // type CompraAgilDetalleResponse"). Si igual no se puede leer, el error
    // incluye el status HTTP y el motivo exacto de Jackson (con la ruta del
    // campo), no solo "Error while extracting response".
    public CompraAgilDetalleResponse getDetalleByCodigo(String codigo) {
        ResponseEntity<String> respuesta = syncClient.get()
                .uri("/v2/compra-agil/{codigo}", codigo)
                .retrieve()
                .toEntity(String.class);
        String crudo = respuesta.getBody();
        if (crudo == null || crudo.isBlank()) {
            throw new IllegalStateException("Detalle de " + codigo + ": body vacio (HTTP "
                    + respuesta.getStatusCode().value() + ")");
        }
        CompraAgilDetalleResponse detalle;
        try {
            detalle = jsonTolerante.readValue(crudo, CompraAgilDetalleResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Detalle de " + codigo + " ilegible (HTTP "
                    + respuesta.getStatusCode().value() + "): " + e.getMessage()
                    + " | body: " + recortar(crudo), e);
        }
        // Sin "success" = no es el envelope de la API (ej. el gateway cortando
        // a los ~29s con {"message": "..."}): se informa tal cual llego.
        if (detalle == null || detalle.success() == null) {
            throw new IllegalStateException("Detalle de " + codigo + ": respuesta inesperada (HTTP "
                    + respuesta.getStatusCode().value() + "): " + recortar(crudo));
        }
        return detalle;
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
                        // tamano_pagina entre 10 y 50: con 1 la API responde
                        // "Parametros de consulta invalidos" y el panel
                        // mostraba la API caida aunque estuviera respondiendo.
                        .uri(uriBuilder -> uriBuilder.path("/v2/compra-agil").queryParam("tamano_pagina", "10").build())
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
