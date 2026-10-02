package cl.zona_ti.licitacion_service.Scheduler;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import cl.zona_ti.licitacion_service.Client.LicitacionAttachmentScraperClient;
import cl.zona_ti.licitacion_service.Client.LicitacionAttachmentScraperClient.AttachmentFile;
import cl.zona_ti.licitacion_service.Dto.LicitacionDto.Licitacion;
import cl.zona_ti.licitacion_service.Dto.LicitacionDto.LicitacionResponse;
import cl.zona_ti.licitacion_service.Model.AdjuntoLicitacionEntity;
import cl.zona_ti.licitacion_service.Repository.AdjuntoLicitacionRepository;
import cl.zona_ti.licitacion_service.Service.LicitacionService;
import cl.zona_ti.licitacion_service.Service.SyncHealthService;
import jakarta.annotation.PreDestroy;

/**
 * Cada X minutos (licitacion-service.sync.fixed-delay):
 *  1) Pide a LicitacionService las licitaciones recientes -- mismo método
 *     que ya usa el frontend (GET /compra/licitacion/listar), que a su vez
 *     consulta la API pública de Mercado Público y guarda cada licitación
 *     en la tabla "licitaciones" (cache). Acá NO se duplica esa llamada a
 *     mano: se reutiliza tal cual.
 *  2) Para cada código, revisa si ya hay adjuntos guardados en
 *     adjunto_licitacion. Los documentos de una licitación no cambian una
 *     vez publicados, así que "ya existe" es suficiente -- no hay TTL acá.
 *  3) Las licitaciones sin adjuntos se encolan en un pool fijo de 2
 *     pedidos a scraper-service en paralelo -- así nunca hay más de 2
 *     descargas (cada una con su propia ventana de Chromium del lado de
 *     scraper-service) corriendo al mismo tiempo, sin importar cuántas
 *     licitaciones nuevas aparezcan en un ciclo.
 *
 * Si no hay nada pendiente, el ciclo no le pega a scraper-service para
 * nada -- el costo caro (levantar Chromium) solo se paga cuando realmente
 * hace falta.
 */
@Component
public class LicitacionSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(LicitacionSyncScheduler.class);

    private static final int POOL_SIZE = 2;

    public static final String JOB_ADJUNTOS = "licitacion-adjuntos";

    // Mismo criterio que CompraAgilSyncScheduler (pedido explicito
    // 2026-10-02, paridad con Api-Prueba): tope de 2 intentos por codigo
    // antes de dejar de reintentar, y sincronizacion mas espaciada en
    // horario nocturno.
    private static final int MAX_INTENTOS_FALLO = 2;
    private static final int HORA_FIN_NOCTURNO = 7;
    private static final long MINUTOS_CICLO_NOCTURNO = 120;
    private final Map<String, Integer> fallosAdjuntos = new ConcurrentHashMap<>();
    private volatile LocalDateTime ultimaEjecucion;

    private final LicitacionAttachmentScraperClient scraper;
    private final AdjuntoLicitacionRepository repository;
    private final LicitacionService licitacionService;
    private final SyncHealthService syncHealthService;
    private final ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE);

    public LicitacionSyncScheduler(LicitacionAttachmentScraperClient scraper,
                                    AdjuntoLicitacionRepository repository,
                                    LicitacionService licitacionService,
                                    SyncHealthService syncHealthService) {
        this.scraper = scraper;
        this.repository = repository;
        this.licitacionService = licitacionService;
        this.syncHealthService = syncHealthService;
    }

    @Scheduled(fixedDelayString = "${licitacion-service.sync.fixed-delay:PT20M}")
    public void sincronizarAdjuntos() {
        LocalDateTime ahora = LocalDateTime.now();
        if (ahora.getHour() < HORA_FIN_NOCTURNO
                && ultimaEjecucion != null
                && Duration.between(ultimaEjecucion, ahora).toMinutes() < MINUTOS_CICLO_NOCTURNO) {
            return;
        }
        ultimaEjecucion = ahora;

        List<String> codigosDelPeriodo = obtenerCodigosLicitacionesRecientes();
        syncHealthService.iniciarCiclo(JOB_ADJUNTOS);
        if (codigosDelPeriodo.isEmpty()) {
            log.debug("Sync adjuntos: no hay licitaciones recientes en este ciclo.");
            return;
        }

        List<String> pendientes = codigosDelPeriodo.stream()
                .filter(this::necesitaSincronizar)
                .filter(codigo -> fallosAdjuntos.getOrDefault(codigo, 0) < MAX_INTENTOS_FALLO)
                .toList();

        if (pendientes.isEmpty()) {
            log.debug("Sync adjuntos: {} licitaciones revisadas, ninguna pendiente.", codigosDelPeriodo.size());
            return;
        }

        log.info("Sync adjuntos: {} licitaciones pendientes de descargar (de {} revisadas).",
                pendientes.size(), codigosDelPeriodo.size());

        for (String codigo : pendientes) {
            pool.submit(() -> descargarYGuardar(codigo));
        }
    }

    // "Ya existe" = ya tiene al menos un adjunto guardado. Sin TTL: los
    // documentos de una licitación publicada no cambian.
    private boolean necesitaSincronizar(String codigo) {
        return repository.findByCodigoLicitacion(codigo).isEmpty();
    }

    private void descargarYGuardar(String codigo) {
        try {
            List<AttachmentFile> archivos = scraper.descargarAdjuntos(codigo);

            LocalDateTime ahora = LocalDateTime.now();
            List<AdjuntoLicitacionEntity> nuevos = archivos.stream().map(a -> {
                AdjuntoLicitacionEntity e = new AdjuntoLicitacionEntity();
                e.setCodigoLicitacion(codigo);
                e.setNombreArchivo(a.nombre());
                e.setContenido(a.contenido());
                e.setTipoContenido(a.tipoContenido());
                e.setTamanoBytes(a.tamanoBytes());
                e.setFechaSync(ahora);
                return e;
            }).toList();

            repository.saveAll(nuevos);
            log.info("Sync adjuntos OK para {}: {} archivo(s).", codigo, nuevos.size());
            syncHealthService.registrarExito(JOB_ADJUNTOS);
            fallosAdjuntos.remove(codigo);

        } catch (Exception e) {
            // No relanzar: un fallo en una licitación no debe tumbar el pool ni
            // afectar a las demás que se están procesando en paralelo. Reintenta
            // en el próximo ciclo hasta agotar MAX_INTENTOS_FALLO (sigue
            // "pendiente" porque no quedó guardada).
            int intentos = fallosAdjuntos.merge(codigo, 1, Integer::sum);
            log.warn("Sync adjuntos FALLÓ para {} ({}/{}): {}", codigo, intentos, MAX_INTENTOS_FALLO, e.getMessage());
            syncHealthService.registrarError(JOB_ADJUNTOS, e.getMessage());
        }
    }

    private List<String> obtenerCodigosLicitacionesRecientes() {
        try {
            LicitacionResponse respuesta = licitacionService.sincronizarUltimosDias();
            if (respuesta == null || respuesta.listado() == null) {
                return List.of();
            }
            return respuesta.listado().stream()
                    .map(Licitacion::codigoExterno)
                    .filter(codigo -> codigo != null && !codigo.isBlank())
                    .toList();
        } catch (Exception e) {
            log.warn("No se pudo obtener el listado de licitaciones recientes: {}", e.getMessage());
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
