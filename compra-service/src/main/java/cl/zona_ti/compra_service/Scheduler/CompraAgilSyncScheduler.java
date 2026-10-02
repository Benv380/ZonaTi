package cl.zona_ti.compra_service.Scheduler;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import cl.zona_ti.compra_service.Dto.CompraAgilDto.CompraAgilListadoResponse;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.Item;
import cl.zona_ti.compra_service.Service.AdjuntoService;
import cl.zona_ti.compra_service.Service.CompraAgilService;
import cl.zona_ti.compra_service.Service.SyncHealthService;
import jakarta.annotation.PreDestroy;

/**
 * Cada X minutos (compra-service.sync.fixed-delay, mismo que
 * LicitacionSyncScheduler):
 *  1) Pide a CompraAgilService.sincronizarUltimasOchoHoras() el listado
 *     reciente (API real) -- ya deja cacheado cada item resumido.
 *  2) Para cada código, pide el detalle completo (getDetalleByCodigo, respeta
 *     su propio TTL) -- ahí es donde se guarda el JSON crudo completo
 *     (CompraAgilEntity.rawJson) y los datos que el listado no trae.
 *  3) Para cada código, sincroniza el listado de adjuntos y descarga el
 *     binario de los que todavía no lo tengan (AdjuntoService.sincronizarBinarios).
 *
 * El usuario nunca dispara esto directo: lee lo que ya quedó cacheado via
 * CompraAgilController (listarUltimasOchoHorasCacheado, getDetalleByCodigo
 * con TTL) y AdjuntoController (descargar, que sirve desde caché si ya está).
 */
@Component
public class CompraAgilSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(CompraAgilSyncScheduler.class);

    private static final int POOL_SIZE = 4;

    // Nombres de job para SyncHealthService -- separados (detalle/adjuntos)
    // porque son 2 llamadas independientes a la API externa, cada una
    // puede estar fallando o no por su cuenta.
    public static final String JOB_DETALLE = "compra-agil-detalle";
    public static final String JOB_ADJUNTOS = "compra-agil-adjuntos";

    // Pedido explicito 2026-10-02 (paridad con Api-Prueba): si un codigo
    // falla 2 veces seguidas, se deja de reintentar -- evita que un
    // bloqueo/caida prolongada de Mercado Publico (ej. el 403 del ticket)
    // haga que los MISMOS codigos se reintenten completos en cada ciclo
    // durante los 3 dias que dura la ventana de codigosProximosACerrar.
    private static final int MAX_INTENTOS_FALLO = 2;
    private final Map<String, Integer> fallosDetalle = new ConcurrentHashMap<>();
    private final Map<String, Integer> fallosAdjuntos = new ConcurrentHashMap<>();

    // Horario nocturno (00:00-06:59): sincronizacion espaciada a cada 2h en
    // vez de cada compra-service.sync.fixed-delay -- menos presion sobre la
    // API externa cuando casi nadie la necesita al instante.
    private static final int HORA_FIN_NOCTURNO = 7;
    private static final long MINUTOS_CICLO_NOCTURNO = 120;
    private volatile LocalDateTime ultimaEjecucion;

    // Pedido explicito 2026-10-02 (paridad con Api-Prueba): adjunto.
    // mercadopublico.cl (API de adjuntos, distinta de la de compra-agil/
    // listado) daba 429 en ~50 de 2000 llamados -- los 4 hilos del pool
    // podian pegarle casi al mismo tiempo. Este espaciador global (no por
    // hilo) obliga a que haya al menos ESPACIADO_ADJUNTOS_MS entre un
    // llamado de adjuntos y el siguiente, sin importar que hilo lo dispare.
    private static final long ESPACIADO_ADJUNTOS_MS = 400;
    private final Object candadoAdjuntos = new Object();
    private long proximoTurnoAdjuntos = 0;

    private void esperarTurnoAdjuntos() {
        long espera;
        synchronized (candadoAdjuntos) {
            long ahora = System.currentTimeMillis();
            long inicio = Math.max(ahora, proximoTurnoAdjuntos);
            espera = inicio - ahora;
            proximoTurnoAdjuntos = inicio + ESPACIADO_ADJUNTOS_MS;
        }
        if (espera > 0) {
            try {
                Thread.sleep(espera);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private final CompraAgilService compraAgilService;
    private final AdjuntoService adjuntoService;
    private final SyncHealthService syncHealthService;
    private final ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE);

    public CompraAgilSyncScheduler(CompraAgilService compraAgilService, AdjuntoService adjuntoService,
            SyncHealthService syncHealthService) {
        this.compraAgilService = compraAgilService;
        this.adjuntoService = adjuntoService;
        this.syncHealthService = syncHealthService;
    }

    @Scheduled(fixedDelayString = "${compra-service.sync.fixed-delay:PT20M}")
    public void sincronizar() {
        LocalDateTime ahora = LocalDateTime.now();
        if (ahora.getHour() < HORA_FIN_NOCTURNO
                && ultimaEjecucion != null
                && Duration.between(ultimaEjecucion, ahora).toMinutes() < MINUTOS_CICLO_NOCTURNO) {
            return;
        }
        ultimaEjecucion = ahora;

        List<String> codigos = obtenerCodigosRecientes();
        syncHealthService.iniciarCiclo(JOB_DETALLE);
        syncHealthService.iniciarCiclo(JOB_ADJUNTOS);
        if (codigos.isEmpty()) {
            log.debug("Sync compra agil: no hay compras recientes en este ciclo.");
            return;
        }

        log.info("Sync compra agil: {} compras a sincronizar (detalle + adjuntos).", codigos.size());
        for (String codigo : codigos) {
            pool.submit(() -> sincronizarUno(codigo));
        }
    }

    private void sincronizarUno(String codigo) {
        if (fallosDetalle.getOrDefault(codigo, 0) < MAX_INTENTOS_FALLO) {
            try {
                compraAgilService.sincronizarDetalle(codigo);
                syncHealthService.registrarExito(JOB_DETALLE);
                fallosDetalle.remove(codigo);
            } catch (Exception e) {
                int intentos = fallosDetalle.merge(codigo, 1, Integer::sum);
                log.warn("Sync compra agil: detalle de {} FALLÓ ({}/{}): {}",
                        codigo, intentos, MAX_INTENTOS_FALLO, e.getMessage());
                syncHealthService.registrarError(JOB_DETALLE, e.getMessage());
            }
        }
        if (fallosAdjuntos.getOrDefault(codigo, 0) < MAX_INTENTOS_FALLO) {
            esperarTurnoAdjuntos();
            try {
                adjuntoService.sincronizarBinarios(codigo);
                syncHealthService.registrarExito(JOB_ADJUNTOS);
                fallosAdjuntos.remove(codigo);
            } catch (Exception e) {
                int intentos = fallosAdjuntos.merge(codigo, 1, Integer::sum);
                log.warn("Sync compra agil: adjuntos de {} FALLARON ({}/{}): {}",
                        codigo, intentos, MAX_INTENTOS_FALLO, e.getMessage());
                syncHealthService.registrarError(JOB_ADJUNTOS, e.getMessage());
            }
        }
    }

    // Union de 2 fuentes -- LinkedHashSet dedupea preservando el orden (las
    // recien publicadas primero, importa poco pero asi queda determinista):
    //   1) lo recien publicado (API en vivo, trae ademas compras nuevas que
    //      todavia ni existen en cache).
    //   2) lo que ya cacheamos y esta por cerrar/cerro hace poco (ver
    //      CompraAgilService.codigosProximosACerrar) -- sin esto, una
    //      compra con cierre lejano (2do llamado) dejaba de actualizarse
    //      ni bien salia de la ventana de publicacion reciente, aunque
    //      Mercado Publico ya la hubiera cerrado/cancelado.
    private List<String> obtenerCodigosRecientes() {
        Set<String> codigos = new LinkedHashSet<>(obtenerCodigosPublicadosRecientemente());
        try {
            codigos.addAll(compraAgilService.codigosProximosACerrar());
        } catch (Exception e) {
            log.warn("No se pudo obtener el listado de compras proximas a cerrar: {}", e.getMessage());
        }
        return List.copyOf(codigos);
    }

    private List<String> obtenerCodigosPublicadosRecientemente() {
        try {
            CompraAgilListadoResponse respuesta = compraAgilService.sincronizarUltimasOchoHoras();
            if (respuesta == null || respuesta.payload() == null || respuesta.payload().items() == null) {
                return List.of();
            }
            return respuesta.payload().items().stream()
                    .map(Item::codigo)
                    .filter(codigo -> codigo != null && !codigo.isBlank())
                    .toList();
        } catch (Exception e) {
            log.warn("No se pudo obtener el listado reciente de compra ágil: {}", e.getMessage());
            return List.of();
        }
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
