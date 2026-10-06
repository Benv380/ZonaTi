package cl.zona_ti.compra_service.Scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import cl.zona_ti.compra_service.Service.AdjuntoService;
import cl.zona_ti.compra_service.Service.CompraAgilService;
import cl.zona_ti.compra_service.Service.SyncHealthService;
import jakarta.annotation.PreDestroy;

/**
 * Cada hora en punto, hora de Chile (compra-service.sync.cron-compra-agil),
 * y una vez al arrancar:
 *  1) Pide a CompraAgilService.sincronizarPublicadasRecientes() el listado
 *     reciente (API real, todas las paginas) -- deja cacheado cada item
 *     resumido y devuelve solo los codigos que necesitan detalle.
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

    // Detalle + adjuntos en paralelo. Eran 4: visto en vivo 2026-10-06, con
    // 4 pedidos de detalle simultaneos la API empezaba a tardar mas de 20s
    // por respuesta ("Read timed out") en varias compras seguidas.
    private static final int POOL_SIZE = 2;

    // Nombres de job para SyncHealthService -- separados (detalle/adjuntos)
    // porque son 2 llamadas independientes a la API externa, cada una
    // puede estar fallando o no por su cuenta.
    public static final String JOB_DETALLE = "compra-agil-detalle";
    public static final String JOB_ADJUNTOS = "compra-agil-adjuntos";

    // Pedido explicito 2026-10-02: si un codigo falla 2 veces seguidas (hoy
    // o en ciclos siguientes, sin exito de por medio), se deja de reintentar
    // -- evita que un bloqueo/caida prolongada de Mercado Publico (ej. el
    // 403 del ticket) haga que los MISMOS codigos se reintenten completos en
    // cada ciclo durante los 3 dias que dura la ventana de
    // codigosProximosACerrar.
    //
    // El bloqueo expira a las VIDA_FALLO: antes era PERMANENTE (hasta
    // reiniciar el contenedor) y el mapa crecia sin limite -- un rato de
    // timeouts de Mercado Publico dejaba esos codigos sin detalle para
    // siempre.
    private static final int MAX_INTENTOS_FALLO = 2;
    private static final Duration VIDA_FALLO = Duration.ofHours(12);

    private record Fallo(int intentos, Instant ultimo) {
    }

    private final Map<String, Fallo> fallosDetalle = new ConcurrentHashMap<>();
    private final Map<String, Fallo> fallosAdjuntos = new ConcurrentHashMap<>();

    // Codigos ya encolados en el pool que todavia no se procesan: si un
    // ciclo se junta con el siguiente (ej. muchas compras pendientes), el
    // mismo codigo no se encola 2 veces.
    private final Set<String> enCola = ConcurrentHashMap.newKeySet();

    // Un solo ciclo a la vez: el cron, la corrida al arrancar y el disparo
    // manual (SyncController) no se superponen -- si uno sigue corriendo,
    // el otro se salta en vez de duplicar llamados a la API.
    private final ReentrantLock cicloEnCurso = new ReentrantLock();

    // Primera corrida un rato despues de arrancar, para no esperar hasta la
    // proxima hora en punto despues de cada reinicio/deploy.
    private static final long SEGUNDOS_TRAS_ARRANQUE = 30;

    // Interruptor de emergencia: en false no se sincroniza nada contra
    // Mercado Publico (docker-compose: SYNC_HABILITADO en el .env).
    @Value("${compra-service.sync.habilitado:true}")
    private boolean syncHabilitado;

    // Pedido explicito 2026-10-02: adjunto.mercadopublico.cl (API de
    // adjuntos, distinta de la de compra-agil/listado) daba 429 en ~50 de
    // 2000 llamados -- los 4 hilos del pool podian pegarle casi al mismo
    // tiempo. Este espaciador global (no por hilo) obliga a que haya al
    // menos ESPACIADO_ADJUNTOS_MS entre un llamado de adjuntos y el
    // siguiente, sin importar que hilo lo dispare.
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
    private final TaskScheduler taskScheduler;
    private final ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE);

    public CompraAgilSyncScheduler(CompraAgilService compraAgilService, AdjuntoService adjuntoService,
            SyncHealthService syncHealthService, TaskScheduler taskScheduler) {
        this.compraAgilService = compraAgilService;
        this.adjuntoService = adjuntoService;
        this.syncHealthService = syncHealthService;
        this.taskScheduler = taskScheduler;
    }

    // Cada hora en punto, en hora de Chile (configurable con
    // compra-service.sync.cron-compra-agil). Antes: cada 20 min desde que
    // arrancaba el proceso, con un "horario nocturno" calculado con la hora
    // del contenedor (UTC), que en Chile caia de 21:00 a 04:00.
    @Scheduled(cron = "${compra-service.sync.cron-compra-agil:0 0 * * * *}", zone = "America/Santiago")
    public void sincronizarProgramado() {
        sincronizar();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void programarPrimeraCorrida() {
        taskScheduler.schedule(this::sincronizar, Instant.now().plusSeconds(SEGUNDOS_TRAS_ARRANQUE));
    }

    // Publico: tambien lo dispara SyncController (sync manual).
    public void sincronizar() {
        if (!syncHabilitado) {
            log.debug("Sync compra agil: deshabilitado (compra-service.sync.habilitado=false).");
            return;
        }
        if (!cicloEnCurso.tryLock()) {
            log.info("Sync compra agil: ya hay un ciclo en curso, se salta este.");
            return;
        }
        try {
            ejecutarCiclo();
        } finally {
            cicloEnCurso.unlock();
        }
    }

    private void ejecutarCiclo() {
        List<String> codigos = obtenerCodigosRecientes();
        syncHealthService.iniciarCiclo(JOB_DETALLE);
        syncHealthService.iniciarCiclo(JOB_ADJUNTOS);
        if (codigos.isEmpty()) {
            log.debug("Sync compra agil: no hay compras recientes en este ciclo.");
            return;
        }

        purgarFallosVencidos(fallosDetalle);
        purgarFallosVencidos(fallosAdjuntos);

        int encolados = 0;
        for (String codigo : codigos) {
            if (!enCola.add(codigo)) {
                continue;
            }
            encolados++;
            try {
                pool.submit(() -> {
                    try {
                        sincronizarUno(codigo);
                    } finally {
                        enCola.remove(codigo);
                    }
                });
            } catch (RuntimeException e) {
                enCola.remove(codigo);
                throw e;
            }
        }
        log.info("Sync compra agil: {} compras a sincronizar (detalle + adjuntos), {} nuevas en cola ({} en cola en total).",
                codigos.size(), encolados, enCola.size());
    }

    private void sincronizarUno(String codigo) {
        if (!bloqueado(fallosDetalle, codigo)) {
            try {
                compraAgilService.sincronizarDetalle(codigo);
                syncHealthService.registrarExito(JOB_DETALLE);
                fallosDetalle.remove(codigo);
            } catch (Exception e) {
                int intentos = registrarFallo(fallosDetalle, codigo);
                log.warn("Sync compra agil: detalle de {} FALLÓ ({}/{}): {}",
                        codigo, intentos, MAX_INTENTOS_FALLO, e.getMessage());
                syncHealthService.registrarError(JOB_DETALLE, e.getMessage());
            }
        }
        if (!bloqueado(fallosAdjuntos, codigo)) {
            esperarTurnoAdjuntos();
            try {
                adjuntoService.sincronizarBinarios(codigo);
                syncHealthService.registrarExito(JOB_ADJUNTOS);
                fallosAdjuntos.remove(codigo);
            } catch (Exception e) {
                int intentos = registrarFallo(fallosAdjuntos, codigo);
                log.warn("Sync compra agil: adjuntos de {} FALLARON ({}/{}): {}",
                        codigo, intentos, MAX_INTENTOS_FALLO, e.getMessage());
                syncHealthService.registrarError(JOB_ADJUNTOS, e.getMessage());
            }
        }
    }

    private static boolean bloqueado(Map<String, Fallo> fallos, String codigo) {
        Fallo fallo = fallos.get(codigo);
        return fallo != null
                && fallo.intentos() >= MAX_INTENTOS_FALLO
                && fallo.ultimo().isAfter(Instant.now().minus(VIDA_FALLO));
    }

    private static int registrarFallo(Map<String, Fallo> fallos, String codigo) {
        Instant ahora = Instant.now();
        return fallos.merge(codigo, new Fallo(1, ahora), (anterior, nuevo) ->
                anterior.ultimo().isBefore(ahora.minus(VIDA_FALLO))
                        ? nuevo
                        : new Fallo(anterior.intentos() + 1, ahora))
                .intentos();
    }

    private static void purgarFallosVencidos(Map<String, Fallo> fallos) {
        Instant limite = Instant.now().minus(VIDA_FALLO);
        fallos.values().removeIf(fallo -> fallo.ultimo().isBefore(limite));
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

    // Ya viene filtrado: solo los codigos que necesitan pedir el detalle (ver
    // CompraAgilService.sincronizarPublicadasRecientes). El resto del
    // listado igual quedo cacheado (y visible en el front) al leerlo.
    private List<String> obtenerCodigosPublicadosRecientemente() {
        try {
            return compraAgilService.sincronizarPublicadasRecientes();
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
