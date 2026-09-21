package cl.zona_ti.compra_service.Controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cl.zona_ti.compra_service.Scheduler.CompraAgilSyncScheduler;

/**
 * Dispara a mano un ciclo de sincronización, sin esperar al próximo tick del
 * scheduler (cada compra-service.sync.fixed-delay) ni reiniciar el
 * contenedor. Pensado para pruebas/operación, no para uso del frontend
 * público.
 *
 * Sync de licitaciones vive en licitacion-service (ver el split de
 * compra-service en 2 servicios) -- este endpoint solo dispara Compra Ágil.
 *
 * sincronizar() empieza con una llamada síncrona a la API externa (para
 * descubrir códigos pendientes) antes de encolar las descargas en su propio
 * pool -- eso puede tardar. Se corre en un hilo aparte para responder "202
 * Accepted" al toque, sin bloquear al que llama ni arriesgarse a un timeout
 * del proxy si tarda.
 */
@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/compra/sync")
public class SyncController {

    private final CompraAgilSyncScheduler compraAgilSyncScheduler;

    public SyncController(CompraAgilSyncScheduler compraAgilSyncScheduler) {
        this.compraAgilSyncScheduler = compraAgilSyncScheduler;
    }

    // POST /compra/sync/compra-agil -- exclusivo GLOBAL, ahora que el
    // servicio exige JWT (ver SecurityConfig). Forzar un ciclo le pega a la
    // API externa para todo el sistema, no es algo que un USER/EMPRESA
    // deba poder disparar.
    @PostMapping("/compra-agil")
    @PreAuthorize("hasRole('GLOBAL')")
    public ResponseEntity<Void> sincronizarCompraAgil() {
        new Thread(compraAgilSyncScheduler::sincronizar, "sync-compra-agil-manual").start();
        return ResponseEntity.accepted().build();
    }
}
