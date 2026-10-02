package cl.zona_ti.licitacion_service.Scheduler;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import cl.zona_ti.licitacion_service.Client.AsignacionInternoClient;
import cl.zona_ti.licitacion_service.Repository.AdjuntoLicitacionRepository;
import cl.zona_ti.licitacion_service.Repository.LicitacionRepository;
import cl.zona_ti.licitacion_service.Service.SyncHealthService;

/**
 * Purga cache vieja de Licitacion para no crecer indefinido en disco (ver
 * "un mes/2 semanas" pedido 2026-09-30, Api-Prueba) -- corre una vez al
 * dia, en 2 etapas:
 *   1) 2 semanas cerrada + SIN intervencion de nadie -> borra solo los
 *      adjuntos (lo que mas pesa, BYTEA).
 *   2) 1 mes cerrada + SIN intervencion -> borra la fila entera (ON DELETE
 *      CASCADE se encarga de los adjuntos remanentes, ver schema.sql).
 *
 * Paridad con Api-Prueba: alla es un solo scheduler que limpia Compra Agil
 * Y Licitacion juntos (un solo servicio) -- aca, al estar separados,
 * compra-service tiene su PROPIA copia de este scheduler (y su propio
 * AsignacionInternoClient) para limpiar compras agiles.
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

    private final LicitacionRepository licitacionRepository;
    private final AdjuntoLicitacionRepository adjuntoLicitacionRepository;
    private final AsignacionInternoClient asignacionInternoClient;
    private final SyncHealthService syncHealthService;

    public LimpiezaScheduler(LicitacionRepository licitacionRepository,
            AdjuntoLicitacionRepository adjuntoLicitacionRepository,
            AsignacionInternoClient asignacionInternoClient, SyncHealthService syncHealthService) {
        this.licitacionRepository = licitacionRepository;
        this.adjuntoLicitacionRepository = adjuntoLicitacionRepository;
        this.asignacionInternoClient = asignacionInternoClient;
        this.syncHealthService = syncHealthService;
    }

    @Scheduled(cron = "${licitacion-service.limpieza.cron:0 30 4 * * *}")
    @Transactional
    public void limpiar() {
        syncHealthService.iniciarCiclo(JOB_LIMPIEZA);
        try {
            // Deja que la excepcion se propague si auth-service no responde
            // -- aborta la limpieza de ESTE ciclo entero, a proposito (ver
            // javadoc de la clase).
            Set<String> conAsignacion = Set.copyOf(asignacionInternoClient.codigosConAsignacion("LICITACION"));
            LocalDateTime ahora = LocalDateTime.now(ZoneOffset.UTC);

            List<String> paraAdjuntos = licitacionRepository.findCodigosCerradosAntesDe(ahora.minusDays(DIAS_BORRAR_ADJUNTOS))
                    .stream().filter(c -> !conAsignacion.contains(c)).toList();
            if (!paraAdjuntos.isEmpty()) {
                adjuntoLicitacionRepository.deleteByCodigoLicitacionIn(paraAdjuntos);
                log.info("Limpieza Licitacion: adjuntos borrados de {} codigo(s) cerrados hace 2+ semanas.", paraAdjuntos.size());
            }

            List<String> paraFila = licitacionRepository.findCodigosCerradosAntesDe(ahora.minusDays(DIAS_BORRAR_FILA))
                    .stream().filter(c -> !conAsignacion.contains(c)).toList();
            if (!paraFila.isEmpty()) {
                // Acá el ON DELETE CASCADE de adjunto_licitacion ya cubre el
                // borrado de adjuntos remanentes.
                licitacionRepository.deleteAllById(paraFila);
                log.info("Limpieza Licitacion: {} fila(s) borradas (cerradas hace 1+ mes).", paraFila.size());
            }
            syncHealthService.registrarExito(JOB_LIMPIEZA);
        } catch (Exception e) {
            log.warn("Limpieza Licitacion FALLO: {}", e.getMessage());
            syncHealthService.registrarError(JOB_LIMPIEZA, e.getMessage());
        }
    }
}
