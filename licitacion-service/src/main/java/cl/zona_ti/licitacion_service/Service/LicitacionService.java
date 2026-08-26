package cl.zona_ti.licitacion_service.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import cl.zona_ti.licitacion_service.Client.LicitacionClient;
import cl.zona_ti.licitacion_service.Dto.LicitacionDto.Licitacion;
import cl.zona_ti.licitacion_service.Dto.LicitacionDto.LicitacionResponse;
import cl.zona_ti.licitacion_service.Mapper.LicitacionMapper;
import cl.zona_ti.licitacion_service.Model.LicitacionEntity;
import cl.zona_ti.licitacion_service.Repository.LicitacionRepository;

@Service
public class LicitacionService {

    private static final ZoneId ZONA_CHILE = ZoneId.of("America/Santiago");
    private static final DateTimeFormatter FORMATO_FECHA_API = DateTimeFormatter.ofPattern("ddMMyyyy");

    private final LicitacionClient licitacionClient;
    private final LicitacionRepository licitacionRepository;
    private final LicitacionMapper licitacionMapper;

    // Cuanto tiempo se considera "fresca" una licitacion cacheada antes de
    // volver a pedirle el detalle a Mercado Publico. TTL propio (no el mismo
    // "ttl-minutos" que usan Compra Agil/Adjuntos) y deliberadamente MAYOR
    // al intervalo del scheduler (LicitacionSyncScheduler, 10 min por
    // defecto): si fueran iguales, el cache nunca llega a servir de nada --
    // cada ciclo pedía de nuevo el detalle de las ~300-400 candidatas del
    // día entero (8 en paralelo) apenas venciendo el TTL anterior, lo que
    // generaba picos de CPU y presión innecesaria sobre la API externa
    // (posible causa de bloqueos/rate-limit que afectaban también a Compra
    // Agil). Una licitacion publicada rara vez cambia su detalle, así que
    // un TTL más largo acá es seguro.
    @Value("${compra-service.cache.ttl-minutos-licitaciones:60}")
    private long ttlMinutos;

    public LicitacionService(LicitacionClient licitacionClient, LicitacionRepository licitacionRepository,
            LicitacionMapper licitacionMapper) {
        this.licitacionClient = licitacionClient;
        this.licitacionRepository = licitacionRepository;
        this.licitacionMapper = licitacionMapper;
    }

    public LicitacionResponse getLicitacionByCodigo(String codigo) {
        Optional<LicitacionEntity> cacheada = licitacionRepository.findByIdConItems(codigo);
        if (cacheada.isPresent() && estaFresca(cacheada.get())) {
            return new LicitacionResponse(1, null, null, List.of(licitacionMapper.toDto(cacheada.get())));
        }

        LicitacionResponse respuesta = licitacionClient.getLicitacionByCodigo(codigo);
        if (respuesta != null && respuesta.listado() != null && !respuesta.listado().isEmpty()) {
            guardarEnCache(respuesta.listado().get(0));
        }
        return respuesta;
    }

    // El listado por fecha viene resumido (sin Fechas/Comprador completos, ver
    // comentario mas abajo), asi que no se cachea ni se sirve desde cache: solo
    // sirve para descubrir codigos candidatos.
    public LicitacionResponse getLicitacionesPorFecha(String fecha) {
        return licitacionClient.getLicitacionesPorFecha(fecha);
    }

    // Cuantos dias hacia atras se trae el listado (ver sincronizarUltimosDias
    // y listarUltimosDiasCacheado).
    // Deliberadamente un rango amplio (no "lo nuevo de hoy"): se muestra todo
    // lo publicado en esta ventana, se haya cerrado ya o no.
    @Value("${compra-service.licitaciones.dias-listado:15}")
    private int diasListado;

    // A diferencia de Compra Agil, la API de licitaciones (licitaciones.json) no
    // admite filtrar por rango de fecha/hora: solo permite pedir el listado
    // completo de un dia (parametro "fecha", formato DDMMAAAA). Por eso aca se
    // pide dia por dia, desde hoy hacia atras "diasListado" dias, y se junta
    // todo en una sola lista.
    //
    // OJO: el listado por fecha viene "resumido" -> Mercado Publico NO entrega
    // ahi el objeto "Fechas" (ni Comprador, Moneda, MontoEstimado, etc), solo
    // vienen completos al consultar la licitacion individual por "codigo". Por
    // eso hay que pedir el detalle de cada candidata del dia antes de poder
    // filtrar por fecha de publicacion real. Ese detalle es lo que se cachea
    // (TTL propio, ver ttlMinutos): si ya tenemos una copia fresca en la base
    // no hace falta pedirlo de nuevo -- importante con una ventana de varios
    // dias, si no cada ciclo del scheduler volveria a pedir todo de nuevo.
    //
    // OJO: este metodo golpea la API externa (una llamada por dia del rango,
    // mas el detalle de cada candidata nueva) -- puede tardar bastante con
    // una ventana de varios dias. NO lo llames desde un endpoint que
    // responde a un request del usuario (eso fue justamente el bug: el
    // controller lo llamaba directo y el front terminaba con 504 esperando).
    // Es exclusivo para que LicitacionSyncScheduler lo corra en background;
    // el usuario lee el resultado ya cacheado via listarUltimosDiasCacheado().
    public LicitacionResponse sincronizarUltimosDias() {
        ZonedDateTime ahora = ZonedDateTime.now(ZONA_CHILE);
        ZonedDateTime desde = ahora.minusDays(diasListado);

        List<Licitacion> candidatas = new ArrayList<>();
        LicitacionResponse ultimaRespuesta = null;

        LocalDate dia = desde.toLocalDate();
        while (!dia.isAfter(ahora.toLocalDate())) {
            try {
                LicitacionResponse respuesta = licitacionClient.getLicitacionesPorFecha(dia.format(FORMATO_FECHA_API));
                if (respuesta != null) {
                    ultimaRespuesta = respuesta;
                    if (respuesta.listado() != null) {
                        candidatas.addAll(respuesta.listado());
                    }
                }
            } catch (Exception ignored) {
                // Si falla el listado de un dia puntual no debe tumbar el resto:
                // se sigue con los demas dias del rango.
            }
            dia = dia.plusDays(1);
        }

        // Se resuelve con un pool propio y acotado (no el parallelStream()
        // directo sobre el common ForkJoinPool): un dia normal trae cientos de
        // candidatas (ej. 376 hoy) y dispararlas todas a la vez contra la API
        // de Mercado Publico puede gatillar rate-limiting externo. 8 en
        // paralelo sigue siendo mucho mas rapido que secuencial sin llegar a
        // sofocar la API externa ni el pool de conexiones a la BD.
        List<Licitacion> filtradas;
        ForkJoinPool poolAcotado = new ForkJoinPool(8);
        try {
            filtradas = poolAcotado.submit(() -> candidatas.parallelStream()
                    .map(this::obtenerDetalleSeguro)
                    .filter(Objects::nonNull)
                    .filter(lic -> estaDentroDelRango(lic, desde, ahora))
                    .toList()).join();
        } finally {
            poolAcotado.shutdown();
        }

        return new LicitacionResponse(
                filtradas.size(),
                ultimaRespuesta != null ? ultimaRespuesta.fechaCreacion() : null,
                ultimaRespuesta != null ? ultimaRespuesta.version() : null,
                filtradas);
    }

    // Version "rapida" para servir al usuario: lee directo de lo que
    // LicitacionSyncScheduler ya sincronizo en background (sin pegarle en
    // vivo a la API externa), asi que responde en milisegundos sin importar
    // cuantos dias abarque la ventana. Es lo que expone el controller.
    public LicitacionResponse listarUltimosDiasCacheado() {
        LocalDateTime desde = ZonedDateTime.now(ZONA_CHILE).minusDays(diasListado).toLocalDateTime();
        List<Licitacion> listado = licitacionRepository.findByFechaPublicacionDesde(desde).stream()
                .map(licitacionMapper::toDto)
                .toList();
        return new LicitacionResponse(listado.size(), null, null, listado);
    }

    // Pide el detalle completo (con Fechas reales) de una candidata resumida,
    // usando la cache si ya la tenemos fresca en vez de volver a golpear la API.
    // Si la API falla para un codigo puntual no debe tumbar el listado completo,
    // simplemente se descarta esa licitacion.
    private Licitacion obtenerDetalleSeguro(Licitacion resumen) {
        // Todo el metodo va dentro de un unico try/catch: corre en hilos del
        // ForkJoinPool (parallelStream), asi que cualquier falla puntual (DB,
        // API externa, mapeo) no debe tumbar el resto del listado.
        try {
            Optional<LicitacionEntity> cacheada = licitacionRepository.findByIdConItems(resumen.codigoExterno());
            if (cacheada.isPresent() && estaFresca(cacheada.get())) {
                return licitacionMapper.toDto(cacheada.get());
            }

            LicitacionResponse detalle = licitacionClient.getLicitacionByCodigo(resumen.codigoExterno());
            if (detalle != null && detalle.listado() != null && !detalle.listado().isEmpty()) {
                Licitacion licitacion = detalle.listado().get(0);
                guardarEnCache(licitacion);
                return licitacion;
            }
        } catch (Exception ignored) {
            // Se descarta la candidata; no interrumpe el resto del listado.
        }
        return null;
    }

    private void guardarEnCache(Licitacion licitacion) {
        try {
            licitacionRepository.save(licitacionMapper.toEntity(licitacion, LocalDateTime.now()));
        } catch (Exception ignored) {
            // Un fallo al escribir en la cache no debe tumbar la respuesta al usuario.
        }
    }

    private boolean estaFresca(LicitacionEntity entity) {
        LocalDateTime fechaSync = entity.getFechaSync();
        return fechaSync != null && fechaSync.isAfter(LocalDateTime.now().minusMinutes(ttlMinutos));
    }

    private boolean estaDentroDelRango(Licitacion licitacion, ZonedDateTime desde, ZonedDateTime hasta) {
        if (licitacion.fechas() == null) {
            return false;
        }
        ZonedDateTime publicacion = parsearFecha(licitacion.fechas().fechaPublicacion());
        if (publicacion == null) {
            // Fecha no parseable con los formatos conocidos: se incluye para no
            // esconder licitaciones recientes por un cambio de formato de la API.
            return true;
        }
        return !publicacion.isBefore(desde) && !publicacion.isAfter(hasta);
    }

    private static final List<DateTimeFormatter> FORMATOS_FECHA_HORA = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"));

    private ZonedDateTime parsearFecha(String fecha) {
        if (fecha == null || fecha.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(fecha).atZoneSameInstant(ZONA_CHILE);
        } catch (DateTimeParseException ignored) {
        }
        for (DateTimeFormatter formato : FORMATOS_FECHA_HORA) {
            try {
                return java.time.LocalDateTime.parse(fecha, formato).atZone(ZONA_CHILE);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }
}
