package cl.zona_ti.compra_service.Service;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import cl.zona_ti.compra_service.Client.ReclamosTokenPythonScraper;

/**
 * Cache del JWT que exige comprador-api-pro.mercadopublico.cl (ver
 * ReclamosClient) -- lo renueva ReclamosTokenScheduler en background
 * (abrir un navegador real tarda varios segundos, nunca se hace en el
 * hilo de un request de usuario). getToken() SOLO lee lo que ya está
 * cacheado, nunca dispara una renovación sincrónica -- si todavía no hay
 * nada cacheado (recién arrancó el servicio) o la última renovación
 * falló, devuelve null y ReclamosClient/CompraAgilService.reclamosDe() ya
 * saben fallar cerrado con eso (ver ahí).
 */
@Service
public class ReclamosTokenCacheService {

    private static final Logger log = LoggerFactory.getLogger(ReclamosTokenCacheService.class);

    // Vencimiento real observado a mano: ~8hs. Se cachea con margen para
    // no quedar sirviendo un token ya vencido entre un ciclo del
    // scheduler y el siguiente.
    @Value("${compra-service.reclamos.token-vida-horas:7}")
    private long vidaHoras;

    private final ReclamosTokenPythonScraper scraper;

    private volatile String tokenActual;
    private volatile Instant expiraEn = Instant.EPOCH;

    public ReclamosTokenCacheService(ReclamosTokenPythonScraper scraper) {
        this.scraper = scraper;
    }

    public String getToken() {
        if (tokenActual == null || Instant.now().isAfter(expiraEn)) {
            return null;
        }
        return tokenActual;
    }

    // La llama ReclamosTokenScheduler -- corre el script Python
    // (Playwright, lento) y actualiza el cache. Si falla, deja el token
    // viejo tal cual: mejor seguir sirviendo uno un poco mas cerca de
    // vencer que quedarse sin nada de golpe por una falla puntual del
    // scraper (un timeout de red, el sitio caido un rato, etc.).
    public synchronized void refrescar(String rutReferencia) {
        try {
            String nuevoToken = scraper.obtenerToken(rutReferencia);
            tokenActual = nuevoToken;
            expiraEn = Instant.now().plusSeconds(vidaHoras * 3600);
            log.info("Token de reclamos renovado OK, vence ~{}", expiraEn);
        } catch (Exception e) {
            log.warn("No se pudo renovar el token de reclamos: {}", e.getMessage());
        }
    }
}
