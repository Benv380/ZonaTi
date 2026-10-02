package cl.zona_ti.compra_service.Scheduler;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import cl.zona_ti.compra_service.Client.AsignacionInternoClient;
import cl.zona_ti.compra_service.Repository.AdjuntoRepository;
import cl.zona_ti.compra_service.Repository.CompraAgilRepository;
import cl.zona_ti.compra_service.Service.SyncHealthService;

/**
 * Purga cache vieja de Compra Agil para no crecer indefinido en disco (ver
 * "un mes/2 semanas" pedido 2026-09-30) -- corre una vez al dia, en 2
 * etapas:
 *   1) 2 semanas cerrada + SIN intervencion de nadie -> borra solo los
 *      adjuntos (lo que mas pesa, BYTEA).
 *   2) 1 mes cerrada + SIN intervencion -> borra la fila entera (adjuntos de
 *      Compra Agil se borran a mano aparte, no tienen FK real -- ver
 *      AdjuntoRepository).
 *
 * Paridad con Api-Prueba: alla es un solo scheduler que limpia Compra Agil
 * Y Licitacion juntos (un solo servicio) -- aca, al estar separados,
 * licitacion-service tiene su PROPIA copia de este scheduler (y su propio
 * AsignacionInternoClient) para limpiar licitaciones.
 *
 * "Intervencion" = tiene AL MENOS una fila en auth-service.asignaciones
 * (cualquier empresa, cualquier estado -- ver AsignacionInternoClient/
 * InternalController en auth-service). Fail-safe a proposito: si no se
 * puede confirmar contra auth-service (timeout, servicio caido, clave mal
 * configurada), se aborta la limpieza de este ciclo entero -- mejor no
 * borrar nada que borrar de mas por asumir "sin asignaciones" ante una
 * falla.
 */
@Component
public class LimpiezaScheduler {

    private static final Logger log = LoggerFactory.getLogger(LimpiezaScheduler.class);

    public static final String JOB_LIMPIEZA = "limpieza-cache";

    private static final int DIAS_BORRAR_ADJUNTOS = 14;
    private static final int DIAS_BORRAR_FILA = 30;

    private final CompraAgilRepository compraAgilRepository;
    private final AdjuntoRepository adjuntoRepository;
    private final AsignacionInternoClient asignacionInternoClient;
    private final SyncHealthService syncHealthService;

    public LimpiezaScheduler(CompraAgilRepository compraAgilRepository, AdjuntoRepository adjuntoRepository,
            AsignacionInternoClient asignacionInternoClient, SyncHealthService syncHealthService) {
        this.compraAgilRepository = compraAgilRepository;
        this.adjuntoRepository = adjuntoRepository;
        this.asignacionInternoClient = asignacionInternoClient;
        this.syncHealthService = syncHealthService;
    }

    @Scheduled(cron = "${compra-service.limpieza.cron:0 0 4 * * *}")
    @Transactional
    public void limpiar() {
        syncHealthService.iniciarCiclo(JOB_LIMPIEZA);
        try {
            // Deja que la excepcion se propague si auth-service no responde
            // -- aborta la limpieza de ESTE ciclo entero, a proposito (ver
            // javadoc de la clase).
            Set<String> conAsignacion = Set.copyOf(asignacionInternoClient.codigosConAsignacion("COMPRA_AGIL"));
            LocalDateTime ahora = LocalDateTime.now(ZoneOffset.UTC);

            List<String> paraAdjuntos = compraAgilRepository.findCodigosCerradosAntesDe(ahora.minusDays(DIAS_BORRAR_ADJUNTOS))
                    .stream().filter(c -> !conAsignacion.contains(c)).toList();
            if (!paraAdjuntos.isEmpty()) {
                adjuntoRepository.deleteByCompraAgilCodigoIn(paraAdjuntos);
                log.info("Limpieza Compra Agil: adjuntos borrados de {} codigo(s) cerrados hace 2+ semanas.", paraAdjuntos.size());
            }

            List<String> paraFila = compraAgilRepository.findCodigosCerradosAntesDe(ahora.minusDays(DIAS_BORRAR_FILA))
                    .stream().filter(c -> !conAsignacion.contains(c)).toList();
            if (!paraFila.isEmpty()) {
                // Defensivo: por si el paso de arriba no corrio para estos
                // codigos (ej. el servicio estuvo caido esos dias) -- sin FK
                // real, no se borran solos al borrar la fila.
                adjuntoRepository.deleteByCompraAgilCodigoIn(paraFila);
                compraAgilRepository.deleteAllById(paraFila);
                log.info("Limpieza Compra Agil: {} fila(s) borradas (cerradas hace 1+ mes).", paraFila.size());
            }
            syncHealthService.registrarExito(JOB_LIMPIEZA);
        } catch (Exception e) {
            log.warn("Limpieza Compra Agil FALLO: {}", e.getMessage());
            syncHealthService.registrarError(JOB_LIMPIEZA, e.getMessage());
        }
    }
}
