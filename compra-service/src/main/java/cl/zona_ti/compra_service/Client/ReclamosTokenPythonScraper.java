package cl.zona_ti.compra_service.Client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Consigue el JWT "anonimo" que exige comprador-api-pro.mercadopublico.cl
 * (ver ReclamosClient/application.yml) invocando como subproceso el script
 * Python scripts/obtener_token_reclamos.py (Playwright) -- ese servidor de
 * auth es distinto al que ya usa AuthClient/TokenCacheService para las
 * demas APIs de Mercado Publico, asi que no se puede reusar ese mecanismo
 * HTTP liviano aca: hay que replicar lo que hace la propia pagina publica
 * de la ficha de un comprador al cargar (visible en las DevTools).
 *
 * Pensado para que lo invoque SOLO un scheduler en background (ver
 * ReclamosTokenScheduler), nunca directo desde un request de usuario --
 * abre un navegador real, tarda varios segundos.
 */
@Component
public class ReclamosTokenPythonScraper {

    private static final Logger log = LoggerFactory.getLogger(ReclamosTokenPythonScraper.class);
    private static final String PREFIJO_TOKEN = "TOKEN=";

    // Igual que en Api-Prueba (mismo entorno Python+Playwright).
    @Value("#{'${compra-service.python.command-prefix:python}'.split(',')}")
    private List<String> commandPrefix;

    @Value("${compra-service.python.reclamos-script-path:scripts/obtener_token_reclamos.py}")
    private String scriptPath;

    @Value("${compra-service.python.timeout-seconds:90}")
    private long timeoutSeconds;

    public String obtenerToken(String rutReferencia) throws IOException {
        List<String> comando = new ArrayList<>(commandPrefix);
        comando.addAll(Arrays.asList(scriptPath, rutReferencia));

        ProcessBuilder pb = new ProcessBuilder(comando);
        pb.redirectErrorStream(true);
        // Sin esto el stdout de Python queda buffereado en bloques al
        // correr conectado a un pipe (no es una terminal real), y se puede
        // perder si el proceso termina abrupto.
        pb.environment().put("PYTHONUNBUFFERED", "1");

        Process proceso = pb.start();
        String salida;
        try (var in = proceso.getInputStream()) {
            salida = new String(in.readAllBytes());
        }

        boolean terminoATiempo;
        try {
            terminoATiempo = proceso.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proceso.destroyForcibly();
            throw new IOException("Interrumpido esperando el script de token de reclamos", e);
        }

        if (!terminoATiempo) {
            proceso.destroyForcibly();
            throw new IOException("El script de token de reclamos no terminó dentro de "
                    + timeoutSeconds + "s. Salida hasta el momento:\n" + salida);
        }

        if (proceso.exitValue() != 0) {
            throw new IOException("El script de token de reclamos terminó con código "
                    + proceso.exitValue() + ". Salida:\n" + salida);
        }

        String token = salida.lines()
                .map(String::trim)
                .filter(linea -> linea.startsWith(PREFIJO_TOKEN))
                .reduce((primero, ultimo) -> ultimo) // si hay mas de una linea TOKEN=, la ultima manda
                .map(linea -> linea.substring(PREFIJO_TOKEN.length()))
                .orElse(null);

        if (token == null || token.isBlank()) {
            throw new IOException("El script de token de reclamos no imprimió ningún TOKEN=. Salida:\n" + salida);
        }

        log.debug("Token de reclamos obtenido OK.");
        return token;
    }
}
