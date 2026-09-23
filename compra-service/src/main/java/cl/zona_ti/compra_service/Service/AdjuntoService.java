package cl.zona_ti.compra_service.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import cl.zona_ti.compra_service.Client.AdjuntoClient;
import cl.zona_ti.compra_service.Dto.AdjuntoDto.AdjuntoArchivo;
import cl.zona_ti.compra_service.Dto.AdjuntoDto.AdjuntoListadoPayload;
import cl.zona_ti.compra_service.Dto.AdjuntoDto.AdjuntoListadoResponse;
import cl.zona_ti.compra_service.Model.AdjuntoEntity;
import cl.zona_ti.compra_service.Repository.AdjuntoRepository;

@Service
public class AdjuntoService {

    private static final Logger log = LoggerFactory.getLogger(AdjuntoService.class);

    private final AdjuntoClient adjuntoClient;
    private final TokenCacheService tokenCacheService;
    private final AdjuntoRepository adjuntoRepository;

    public AdjuntoService(AdjuntoClient adjuntoClient, TokenCacheService tokenCacheService,
            AdjuntoRepository adjuntoRepository) {
        this.adjuntoClient = adjuntoClient;
        this.tokenCacheService = tokenCacheService;
        this.adjuntoRepository = adjuntoRepository;
    }

    // Uso EXCLUSIVO de sincronizarBinarios (via CompraAgilSyncScheduler) --
    // esta es la UNICA via que le pega en vivo a Mercado Publico para el
    // listado de adjuntos, es lo que alimenta la cache. No confundir con
    // listar() de abajo (esa es 100% cache, la usa el usuario).
    private AdjuntoListadoResponse sincronizarListado(String codigoCompra) {
        AdjuntoListadoResponse respuesta = adjuntoClient.listar(codigoCompra);
        guardarEnCache(codigoCompra, respuesta);
        return respuesta;
    }

    // 100% CACHE, nunca en vivo (mismo criterio que CompraAgilService.
    // getDetalleByCodigo -- la base tiene que sostener el sistema aunque
    // Mercado Publico este caido/lento). Si todavia no se sincronizo nada
    // para este codigo, devuelve lista vacia -- a diferencia del detalle,
    // ac치 no hace falta un mensaje de error especial: "sin adjuntos" y
    // "todavia no sincronizado" se ven exactamente igual en la interfaz, y
    // la mayoria de las compras genuinamente no tienen ningun adjunto.
    public AdjuntoListadoResponse listar(String codigoCompra) {
        List<AdjuntoEntity> cacheados = adjuntoRepository.findByCompraAgilCodigo(codigoCompra);
        return construirRespuestaDesdeCache(cacheados);
    }

    public ResponseEntity<byte[]> descargar(String uuid) {
        // Primero el cache: si CompraAgilSyncScheduler ya bajo el binario, se
        // sirve directo de la BD (rapido, sin depender de que
        // adjunto.mercadopublico.cl este arriba/accesible en este momento).
        Optional<AdjuntoEntity> cacheado = adjuntoRepository.findById(uuid);
        if (cacheado.isPresent() && cacheado.get().getContenido() != null) {
            AdjuntoEntity entidad = cacheado.get();
            MediaType tipo;
            try {
                tipo = entidad.getTipoContenido() != null
                        ? MediaType.parseMediaType(entidad.getTipoContenido())
                        : MediaType.APPLICATION_OCTET_STREAM;
            } catch (Exception e) {
                tipo = MediaType.APPLICATION_OCTET_STREAM;
            }
            return ResponseEntity.ok()
                    .contentType(tipo)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .body(entidad.getContenido());
        }

        // Sin cache todavia (el scheduler no paso por este archivo, o fallo):
        // camino viejo, streaming en vivo.
        ResponseEntity<byte[]> original = adjuntoClient.descargar(uuid);

        MediaType contentType = original.getHeaders().getContentType();
        if (contentType == null) {
            contentType = MediaType.APPLICATION_OCTET_STREAM;
        }

        return ResponseEntity.status(original.getStatusCode())
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(original.getBody());
    }

    // Baja y guarda el binario de cada adjunto de esta compra que todavia no
    // lo tenga cacheado. Pensado para correr en background (ver
    // CompraAgilSyncScheduler), nunca desde un request de usuario -- puede
    // implicar varias descargas secuenciales.
    public void sincronizarBinarios(String codigoCompra) {
        sincronizarListado(codigoCompra); // asegura que el listado (metadata) este al dia

        List<AdjuntoEntity> pendientes = adjuntoRepository.findByCompraAgilCodigo(codigoCompra).stream()
                .filter(a -> a.getContenido() == null)
                .toList();

        for (AdjuntoEntity adjunto : pendientes) {
            try {
                ResponseEntity<byte[]> respuesta = adjuntoClient.descargar(adjunto.getId());
                byte[] body = respuesta.getBody();
                if (respuesta.getStatusCode().isError() || body == null || body.length == 0) {
                    log.warn("Adjunto {} de {} no se pudo descargar (status {}).",
                            adjunto.getId(), codigoCompra, respuesta.getStatusCode());
                    continue;
                }
                adjunto.setContenido(body);
                adjunto.setTamanoBytes(body.length);
                MediaType tipo = respuesta.getHeaders().getContentType();
                adjunto.setTipoContenido(tipo != null ? tipo.toString() : null);
                adjuntoRepository.save(adjunto);
            } catch (Exception e) {
                log.warn("Adjunto {} de {} FALLÓ: {}", adjunto.getId(), codigoCompra, e.getMessage());
            }
        }
    }

    // Se deja disponible por si en el futuro se necesita en el frontend,
    // aunque ya no se usa para descargar directo (bloqueado por CORS).
    public Map<String, String> obtenerCredencialesParaFrontend() {
        return Map.of(
                "access_token", tokenCacheService.getToken(),
                "user_key", adjuntoClient.getUserKey()
        );
    }

    private AdjuntoListadoResponse construirRespuestaDesdeCache(List<AdjuntoEntity> cacheados) {
        List<AdjuntoArchivo> files = cacheados.stream()
                .map(entity -> new AdjuntoArchivo(entity.getId(), entity.getNombreArchivo()))
                .toList();
        return new AdjuntoListadoResponse("true", null, new AdjuntoListadoPayload(files), null);
    }

    private void guardarEnCache(String codigoCompra, AdjuntoListadoResponse respuesta) {
        if (respuesta == null || respuesta.payload() == null || respuesta.payload().files() == null) {
            return;
        }
        try {
            // OJO: ya NO se borra y recrea todo (como antes) -- eso tiraba a la
            // basura el binario ya descargado (ver sincronizarBinarios) en cada
            // refresco del listado. Se reutiliza la fila existente por id
            // (preserva "contenido" si ya estaba) y solo se borra lo que
            // realmente ya no aparece en la respuesta.
            Map<String, AdjuntoEntity> existentesPorId = new HashMap<>();
            for (AdjuntoEntity existente : adjuntoRepository.findByCompraAgilCodigo(codigoCompra)) {
                existentesPorId.put(existente.getId(), existente);
            }

            LocalDateTime ahora = LocalDateTime.now();
            List<AdjuntoEntity> actualizados = new ArrayList<>();
            for (AdjuntoArchivo archivo : respuesta.payload().files()) {
                AdjuntoEntity entity = existentesPorId.remove(archivo.id());
                if (entity == null) {
                    entity = new AdjuntoEntity();
                    entity.setId(archivo.id());
                    entity.setCompraAgilCodigo(codigoCompra);
                }
                entity.setNombreArchivo(archivo.nombreArchivo());
                entity.setFechaSync(ahora);
                actualizados.add(entity);
            }
            adjuntoRepository.saveAll(actualizados);

            // Lo que quedo en existentesPorId ya no vino en la respuesta: se borra
            // de verdad (huerfano real, no un simple refresco de los mismos ids).
            if (!existentesPorId.isEmpty()) {
                adjuntoRepository.deleteAll(existentesPorId.values());
            }
        } catch (Exception ignored) {
            // Un fallo al escribir en la cache no debe tumbar la respuesta al usuario.
        }
    }
}
