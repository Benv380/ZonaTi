package cl.zona_ti.licitacion_service.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import cl.zona_ti.licitacion_service.Client.LicitacionClient;
import cl.zona_ti.licitacion_service.Client.PerfilClient;
import cl.zona_ti.licitacion_service.Dto.LicitacionDto.Licitacion;
import cl.zona_ti.licitacion_service.Dto.LicitacionDto.LicitacionResponse;
import cl.zona_ti.licitacion_service.Dto.LicitacionDto.Paginacion;
import cl.zona_ti.licitacion_service.Dto.PerfilBusquedaDto;
import cl.zona_ti.licitacion_service.Mapper.LicitacionMapper;
import cl.zona_ti.licitacion_service.Model.Alcance;
import cl.zona_ti.licitacion_service.Model.LicitacionEntity;
import cl.zona_ti.licitacion_service.Repository.LicitacionRepository;
import cl.zona_ti.licitacion_service.Security.AuthenticatedPrincipal;

@Service
public class LicitacionService {

    private static final ZoneId ZONA_CHILE = ZoneId.of("America/Santiago");
    private static final DateTimeFormatter FORMATO_FECHA_API = DateTimeFormatter.ofPattern("ddMMyyyy");

    private final LicitacionClient licitacionClient;
    private final LicitacionRepository licitacionRepository;
    private final LicitacionMapper licitacionMapper;
    private final PerfilClient perfilClient;

    // Tamaño de pagina por defecto para /listar y /mi-filtro -- ver
    // paginar(). 15 es lo que pidio el usuario para que la pagina no quede
    // interminable.
    private static final int TAMANO_PAGINA_DEFECTO = 15;

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
    @Value("${licitacion-service.cache.ttl-minutos:60}")
    private long ttlMinutos;

    public LicitacionService(LicitacionClient licitacionClient, LicitacionRepository licitacionRepository,
            LicitacionMapper licitacionMapper, PerfilClient perfilClient) {
        this.licitacionClient = licitacionClient;
        this.licitacionRepository = licitacionRepository;
        this.licitacionMapper = licitacionMapper;
        this.perfilClient = perfilClient;
    }

    // Busqueda por codigo puntual -- ya no se acota por asignaciones (ver
    // listarUltimosDiasCacheado): el catalogo completo de Mercado Publico
    // es visible para cualquier usuario autenticado, asignaciones ahora
    // solo trackea el trabajo propio ("Mis activas" en el front), no la
    // visibilidad del catalogo.
    //
    // 100% CACHE, nunca en vivo (decision 2026-09-23: la base tiene que
    // sostener el sistema aunque Mercado Publico este caido/lento -- antes
    // esto le pegaba en vivo como respaldo cuando no encontraba nada
    // cacheado, y ese respaldo era la fuente de los timeouts). El detalle
    // completo es lo unico que se guarda en licitacionRepository (el
    // listado por fecha, resumido, nunca se cachea -- ver comentario en
    // getLicitacionesPorFecha), asi que estar presente ya alcanza, no hace
    // falta chequear "frescura" aca (a diferencia de obtenerDetalleSeguro,
    // que sigue usando estaFresca() para decidir si vale la pena pedirla
    // de nuevo durante el sync).
    public LicitacionResponse getLicitacionByCodigo(String codigo, AuthenticatedPrincipal principal, String authorizationHeader) {
        Optional<LicitacionEntity> cacheada = licitacionRepository.findByIdConItems(codigo);
        if (cacheada.isPresent()) {
            return new LicitacionResponse(1, null, null, List.of(licitacionMapper.toDto(cacheada.get())));
        }

        // El codigo tal cual no encontro nada -- puede que el usuario lo
        // haya escrito con guiones de menos/de mas, espacios, o en
        // minuscula. Se busca en el cache local (misma ventana que
        // /listar) un codigo cuya version normalizada (sin simbolos, en
        // mayusculas) coincida EXACTO -- a diferencia de "adivinar" donde
        // van los guiones, esto compara contra codigos REALES que ya
        // vinieron de Mercado Publico, asi que no hay riesgo de matchear
        // algo que no corresponde. Si el codigo nunca se sincronizo (no
        // esta en la ventana cacheada), esto no puede ayudar -- ahi no
        // hay nada contra que comparar.
        String codigoReal = buscarCodigoNormalizado(codigo);
        if (codigoReal != null) {
            Optional<LicitacionEntity> porNormalizado = licitacionRepository.findByIdConItems(codigoReal);
            if (porNormalizado.isPresent()) {
                return new LicitacionResponse(1, null, null, List.of(licitacionMapper.toDto(porNormalizado.get())));
            }
        }

        return new LicitacionResponse(0, null, null, List.of());
    }

    private static String normalizarParaComparar(String valor) {
        return valor == null ? "" : valor.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
    }

    private String buscarCodigoNormalizado(String entrada) {
        String entradaNormalizada = normalizarParaComparar(entrada);
        if (entradaNormalizada.isBlank()) {
            return null;
        }
        LocalDateTime desde = ZonedDateTime.now(ZONA_CHILE).minusDays(diasListado).toLocalDateTime();
        return licitacionRepository.findByFechaPublicacionDesde(desde).stream()
                .map(LicitacionEntity::getCodigoExterno)
                .filter(codigoCacheado -> normalizarParaComparar(codigoCacheado).equals(entradaNormalizada))
                .findFirst()
                .orElse(null);
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
    @Value("${licitacion-service.dias-listado:15}")
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
    //
    // Ya NO se acota por asignaciones individuales -- cualquier usuario
    // autenticado ve la cartera completa (se decidio asi para que pueda
    // recomendarle a su supervisor algo que todavia no le asignaron a el).
    // "pagina"/"tamano" evitan mandar de un tiron las ~300-400 licitaciones
    // de la ventana entera -- ver paginar().
    public LicitacionResponse listarUltimosDiasCacheado(AuthenticatedPrincipal principal, String authorizationHeader, int pagina, int tamano) {
        LocalDateTime desde = ZonedDateTime.now(ZONA_CHILE).minusDays(diasListado).toLocalDateTime();
        List<Licitacion> listado = licitacionRepository.findByFechaPublicacionDesde(desde).stream()
                .map(licitacionMapper::toDto)
                .toList();

        return paginar(listado, pagina, tamano);
    }

    // "Ver mi filtro" -- distinto de listarUltimosDiasCacheado: esto filtra
    // por el PERFIL DE LA EMPRESA (rubro/palabras clave/region), el mismo
    // concepto que ya existe para Compra Agil (CompraAgilService.buscar())
    // pero implementado distinto -- la API de licitaciones (licitaciones.json,
    // v1) no soporta busqueda en vivo por texto/region como si soporta la
    // v2 de Compra Agil, asi que en vez de reenviar filtros a Mercado
    // Publico, se filtra LOCALMENTE lo que ya esta cacheado (mismo
    // universo que "ver todo", solo que acotado).
    //
    // GLOBAL no tiene empresa -- para ese caso esto devuelve lo mismo que
    // "ver todo" (no hay perfil que aplicar). EMPRESA/USUARIO: si la
    // empresa no tiene ningun filtro guardado, o auth-service no responde,
    // se devuelve vacio -- mismo criterio "fail closed" que el resto de
    // los filtros por perfil. Multi-filtro (2026-09-23): una empresa puede
    // tener VARIOS filtros -- se busca la UNION de todos (una licitacion
    // aparece si matchea AL MENOS uno), deduplicando por codigoExterno.
    public LicitacionResponse buscarConFiltroEmpresa(AuthenticatedPrincipal principal, String authorizationHeader, int pagina, int tamano) {
        if (principal == null || principal.alcance() == Alcance.GLOBAL) {
            return listarUltimosDiasCacheado(principal, authorizationHeader, pagina, tamano);
        }

        List<PerfilBusquedaDto> perfiles = obtenerPerfiles(authorizationHeader);
        if (perfiles.isEmpty()) {
            return new LicitacionResponse(0, null, null, List.of());
        }

        LocalDateTime desde = ZonedDateTime.now(ZONA_CHILE).minusDays(diasListado).toLocalDateTime();
        List<Licitacion> cacheadas = licitacionRepository.findByFechaPublicacionDesde(desde).stream()
                .map(licitacionMapper::toDto)
                .toList();

        LinkedHashMap<String, Licitacion> combinadas = new LinkedHashMap<>();
        for (PerfilBusquedaDto perfil : perfiles) {
            if (perfil.palabrasClave() == null || perfil.palabrasClave().isBlank()) {
                continue;
            }
            List<String> palabras = List.of(perfil.palabrasClave().toLowerCase().split("\\s+"));
            for (Licitacion licitacion : cacheadas) {
                if (coincideConPerfil(licitacion, palabras, perfil.regionNombre())) {
                    combinadas.putIfAbsent(licitacion.codigoExterno(), licitacion);
                }
            }
        }

        return paginar(List.copyOf(combinadas.values()), pagina, tamano);
    }

    // Recorta "listado" (ya ordenado por fecha de publicacion DESC, ver
    // LicitacionRepository.findByFechaPublicacionDesde) a la pagina
    // pedida, sin volver a golpear la base -- ya esta todo en memoria.
    // "pagina" es 1-based; valores invalidos (0, negativos, o mas alla del
    // final) se corrigen en vez de tirar error, para que el front no
    // tenga que hilar fino con los limites.
    private LicitacionResponse paginar(List<Licitacion> listado, int pagina, int tamano) {
        int tam = tamano > 0 ? tamano : TAMANO_PAGINA_DEFECTO;
        int totalPaginas = Math.max(1, (int) Math.ceil(listado.size() / (double) tam));
        int pag = Math.min(Math.max(pagina, 1), totalPaginas);

        int desdeIdx = (pag - 1) * tam;
        int hastaIdx = Math.min(desdeIdx + tam, listado.size());
        List<Licitacion> pagina_ = desdeIdx < hastaIdx ? listado.subList(desdeIdx, hastaIdx) : List.of();

        return new LicitacionResponse(listado.size(), null, null, pagina_,
                new Paginacion(totalPaginas, pag, tam, listado.size()));
    }

    private boolean coincideConPerfil(Licitacion licitacion, List<String> palabrasClave, String regionNombre) {
        String texto = ((licitacion.nombre() != null ? licitacion.nombre() : "")
                + " " + (licitacion.descripcion() != null ? licitacion.descripcion() : "")).toLowerCase();
        boolean coincideTexto = palabrasClave.stream().anyMatch(texto::contains);
        if (!coincideTexto) {
            return false;
        }
        if (regionNombre == null || regionNombre.isBlank()) {
            return true;
        }
        String regionLicitacion = licitacion.comprador() != null ? licitacion.comprador().regionUnidad() : null;
        return regionLicitacion != null && regionLicitacion.toLowerCase().contains(regionNombre.toLowerCase());
    }

    private List<PerfilBusquedaDto> obtenerPerfiles(String authorizationHeader) {
        try {
            return perfilClient.misPerfiles(authorizationHeader);
        } catch (Exception e) {
            return List.of();
        }
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
