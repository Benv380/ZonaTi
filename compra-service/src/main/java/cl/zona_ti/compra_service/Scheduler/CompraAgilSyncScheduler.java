package cl.zona_ti.compra_service.Scheduler;

import java.util.List;
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

    private final CompraAgilService compraAgilService;
    private final AdjuntoService adjuntoService;
    private final ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE);

    public CompraAgilSyncScheduler(CompraAgilService compraAgilService, AdjuntoService adjuntoService) {
        this.compraAgilService = compraAgilService;
        this.adjuntoService = adjuntoService;
    }

    @Scheduled(fixedDelayString = "${compra-service.sync.fixed-delay:PT10M}")
    public void sincronizar() {
        List<String> codigos = obtenerCodigosRecientes();
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
        try {
            compraAgilService.getDetalleByCodigo(codigo);
        } catch (Exception e) {
            log.warn("Sync compra agil: detalle de {} FALLÓ: {}", codigo, e.getMessage());
        }
        try {
            adjuntoService.sincronizarBinarios(codigo);
        } catch (Exception e) {
            log.warn("Sync compra agil: adjuntos de {} FALLARON: {}", codigo, e.getMessage());
        }
    }

    private List<String> obtenerCodigosRecientes() {
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
