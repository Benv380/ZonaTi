package cl.zona_ti.compra_service.Controller;

import org.springframework.http.ResponseEntity;
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
 * sincronizar() empieza con una llamada síncrona a la API externa (para
 * descubrir códigos pendientes) antes de encolar el resto en su propio
 * pool -- eso puede tardar. Se corre en un hilo aparte para responder
 * "202 Accepted" al toque, sin bloquear al que llama ni arriesgarse a un
 * timeout del proxy si tarda.
 */
@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/compra/sync")
public class SyncController {

    private final CompraAgilSyncScheduler compraAgilSyncScheduler;

    public SyncController(CompraAgilSyncScheduler compraAgilSyncScheduler) {
        this.compraAgilSyncScheduler = compraAgilSyncScheduler;
    }

    // POST /compra/sync/compra-agil
    @PostMapping("/compra-agil")
    public ResponseEntity<Void> sincronizarCompraAgil() {
        new Thread(compraAgilSyncScheduler::sincronizar, "sync-compra-agil-manual").start();
        return ResponseEntity.accepted().build();
    }
}
