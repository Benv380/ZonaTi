package cl.zona_ti.licitacion_service.Controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cl.zona_ti.licitacion_service.Scheduler.LicitacionSyncScheduler;
import cl.zona_ti.licitacion_service.Service.SyncHealthService;
import cl.zona_ti.licitacion_service.Service.SyncHealthService.EstadoJob;

/**
 * Dispara a mano un ciclo de sincronización, sin esperar al próximo tick del
 * scheduler (cada licitacion-service.sync.fixed-delay) ni reiniciar el
 * contenedor. Pensado para pruebas/operación, no para uso del frontend
 * público.
 *
 * Sync de Compra Ágil vive en compra-service (ver el split de
 * compra-service en 2 servicios) -- este endpoint solo dispara Licitación.
 *
 * sincronizarAdjuntos() empieza con una llamada síncrona a la API externa
 * (para descubrir códigos pendientes) antes de encolar las descargas en su
 * propio pool -- eso puede tardar. Se corre en un hilo aparte para responder
 * "202 Accepted" al toque, sin bloquear al que llama ni arriesgarse a un
 * timeout del proxy si tarda.
 */
@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/compra/sync")
public class SyncController {

    private final LicitacionSyncScheduler licitacionSyncScheduler;
    private final SyncHealthService syncHealthService;

    public SyncController(LicitacionSyncScheduler licitacionSyncScheduler, SyncHealthService syncHealthService) {
        this.licitacionSyncScheduler = licitacionSyncScheduler;
        this.syncHealthService = syncHealthService;
    }

    // POST /compra/sync/licitaciones -- exclusivo GLOBAL, ahora que el
    // servicio exige JWT (ver SecurityConfig). Forzar un ciclo le pega a la
    // API externa para todo el sistema, no es algo que un USER/EMPRESA
    // deba poder disparar.
    @PostMapping("/licitaciones")
    @PreAuthorize("hasRole('GLOBAL')")
    public ResponseEntity<Void> sincronizarLicitaciones() {
        new Thread(licitacionSyncScheduler::sincronizarAdjuntos, "sync-licitaciones-manual").start();
        return ResponseEntity.accepted().build();
    }

    // GET /compra/sync/salud-licitacion -- exclusivo GLOBAL. Panel de
    // monitoreo en Administracion.jsx: estado de los schedulers de ESTE
    // servicio (adjuntos de licitacion, limpieza). Path DISTINTO al
    // "/compra/sync/salud" de compra-service a proposito -- el gateway
    // rutea por path literal (ver GatewayRoutesConfig), necesita un nombre
    // propio para saber a cual de los 2 servicios mandar cada uno. Sin
    // chequeo en vivo de Mercado Publico (a diferencia de compra-service)
    // -- Api-Prueba tampoco lo tiene para Licitacion, no es gold-plating
    // agregarlo aca.
    @GetMapping("/salud-licitacion")
    @PreAuthorize("hasRole('GLOBAL')")
    public List<EstadoJob> salud() {
        return syncHealthService.snapshot();
    }
}
