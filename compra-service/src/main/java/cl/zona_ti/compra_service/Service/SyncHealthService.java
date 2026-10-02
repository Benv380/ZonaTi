package cl.zona_ti.compra_service.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

// Registro en memoria (no persiste -- se resetea si el backend reinicia) de
// como le esta yendo a cada scheduler que le pega a una API externa
// (Mercado Publico, el scraper Python de licitaciones/reclamos). Pensado
// para el panel de monitoreo de Administracion (ver SyncController.salud())
// -- antes esta info solo vivia en los logs de Docker, sin forma de
// consultarla desde la app. Cada scheduler llama iniciarCiclo() al empezar
// una pasada y registrarExito()/registrarError() por cada item que procesa.
@Service
public class SyncHealthService {

    public record EstadoJob(
            String job,
            LocalDateTime cicloIniciadoEn,
            LocalDateTime ultimoExitoEn,
            LocalDateTime ultimoErrorEn,
            String ultimoError,
            int exitosCicloActual,
            int erroresCicloActual) {
    }

    private static class Estado {
        volatile LocalDateTime cicloIniciadoEn;
        volatile LocalDateTime ultimoExitoEn;
        volatile LocalDateTime ultimoErrorEn;
        volatile String ultimoError;
        final AtomicInteger exitosCicloActual = new AtomicInteger();
        final AtomicInteger erroresCicloActual = new AtomicInteger();
    }

    private final Map<String, Estado> estados = new ConcurrentHashMap<>();

    private Estado estadoDe(String job) {
        return estados.computeIfAbsent(job, k -> new Estado());
    }

    public void iniciarCiclo(String job) {
        Estado e = estadoDe(job);
        e.cicloIniciadoEn = LocalDateTime.now();
        e.exitosCicloActual.set(0);
        e.erroresCicloActual.set(0);
    }

    public void registrarExito(String job) {
        Estado e = estadoDe(job);
        e.ultimoExitoEn = LocalDateTime.now();
        e.exitosCicloActual.incrementAndGet();
    }

    public void registrarError(String job, String mensaje) {
        Estado e = estadoDe(job);
        e.ultimoErrorEn = LocalDateTime.now();
        e.ultimoError = mensaje;
        e.erroresCicloActual.incrementAndGet();
    }

    public List<EstadoJob> snapshot() {
        return estados.entrySet().stream()
                .map(en -> {
                    Estado e = en.getValue();
                    return new EstadoJob(en.getKey(), e.cicloIniciadoEn, e.ultimoExitoEn, e.ultimoErrorEn,
                            e.ultimoError, e.exitosCicloActual.get(), e.erroresCicloActual.get());
                })
                .sorted((a, b) -> a.job().compareTo(b.job()))
                .toList();
    }
}
