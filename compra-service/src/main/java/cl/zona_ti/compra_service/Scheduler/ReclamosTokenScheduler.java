package cl.zona_ti.compra_service.Scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import cl.zona_ti.compra_service.Service.ReclamosTokenCacheService;

/**
 * Renueva el token de comprador-api-pro.mercadopublico.cl antes de que
 * venza (~8hs reales, ver ReclamosTokenCacheService) -- corre cada 6hs por
 * defecto (compra-service.reclamos.token-refresh-delay), incluida una vez
 * al arrancar (initialDelay=0) para no esperar el primer ciclo completo
 * con el panel de reclamos sin datos.
 */
@Component
public class ReclamosTokenScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReclamosTokenScheduler.class);

    // RUT de cualquier organismo publico, solo para cargar la ficha y que
    // la pagina misma pida su token -- no hace falta que tenga reclamos
    // (ver comentario en application.yml, mercado-publico.comprador).
    @Value("${mercado-publico.comprador.rut-referencia:}")
    private String rutReferencia;

    private final ReclamosTokenCacheService cacheService;

    public ReclamosTokenScheduler(ReclamosTokenCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${compra-service.reclamos.token-refresh-delay:PT6H}")
    public void renovar() {
        if (rutReferencia == null || rutReferencia.isBlank()) {
            log.debug("mercado-publico.comprador.rut-referencia no configurado -- no se renueva el token de reclamos.");
            return;
        }
        log.info("Renovando token de reclamos (rut de referencia: {})...", rutReferencia);
        cacheService.refrescar(rutReferencia);
    }
}
