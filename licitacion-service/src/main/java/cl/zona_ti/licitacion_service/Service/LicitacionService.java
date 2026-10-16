package cl.zona_ti.licitacion_service.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import cl.zona_ti.licitacion_service.Client.LicitacionClient;
import cl.zona_ti.licitacion_service.Client.PerfilClient;
import cl.zona_ti.licitacion_service.Dto.LicitacionDto.FiltrosVista;
import cl.zona_ti.licitacion_service.Dto.LicitacionDto.Licitacion;
import cl.zona_ti.licitacion_service.Dto.LicitacionDto.LicitacionResponse;
import cl.zona_ti.licitacion_service.Dto.LicitacionDto.Paginacion;
import cl.zona_ti.licitacion_service.Dto.PerfilBusquedaDto;
import cl.zona_ti.licitacion_service.Mapper.LicitacionMapper;
import cl.zona_ti.licitacion_service.Model.Alcance;
import cl.zona_ti.licitacion_service.Model.LicitacionEntity;
import cl.zona_ti.licitacion_service.Model.LicitacionItemEntity;
import cl.zona_ti.licitacion_service.Repository.LicitacionRepository;
import cl.zona_ti.licitacion_service.Security.AuthenticatedPrincipal;
import cl.zona_ti.licitacion_service.Util.FechaParser;
import cl.zona_ti.licitacion_service.Util.FiltroPalabrasClave;

@Service
public class LicitacionService {

    private static final ZoneId ZONA_CHILE = ZoneId.of("America/Santiago");
    private static final DateTimeFormatter FORMATO_FECHA_API = DateTimeFormatter.ofPattern("ddMMyyyy");

    private final LicitacionClient licitacionClient;
    private final LicitacionRepository licitacionRepository;
    private final LicitacionMapper licitacionMapper;
    private final PerfilClient perfilClient;
    private final CatalogoService catalogoService;

    // Tamaño de pagina por defecto para /listar y /mi-filtro -- ver
    // paginar(). 15 es lo que pidio el usuario para que la pagina no quede
    // interminable.
    private static final int TAMANO_PAGINA_DEFECTO = 15;

    // Cuanto tiempo se considera "fresca" una licitacion cacheada antes de
    // volver a pedirle el detalle a Mercado Publico. TTL propio (no el mismo
    // "ttl-minutos" que usan Compra Agil/Adjuntos) y deliberadamente MAYOR
    // al intervalo del scheduler (LicitacionSyncScheduler): si fueran
    // iguales, el cache nunca llega a servir de nada -- cada ciclo pedía de
    // nuevo el detalle de las ~300-400 candidatas del día entero apenas
    // venciendo el TTL anterior, lo que generaba picos de CPU y presión
    // innecesaria sobre la API externa (posible causa de bloqueos/
    // rate-limit que afectaban también a Compra Agil). Una licitacion
    // publicada rara vez cambia su detalle, así que un TTL más largo acá es
    // seguro.
    @Value("${licitacion-service.cache.ttl-minutos:60}")
    private long ttlMinutos;

    // Cuantos detalles se piden en paralelo durante el sync. Eran 8 fijos
    // sobre el MISMO ticket: la API v1 rechaza peticiones simultaneas del
    // mismo ticket, y la cuota diaria de licitaciones se agotaba (visto en
    // vivo 2026-10-06, 429).
    @Value("${licitacion-service.licitaciones.paralelismo-detalle:2}")
    private int paralelismoDetalle;

    // Aunque el TTL haya vencido, el detalle NO se vuelve a pedir si el
    // listado dice que no cambio ni el estado ni la fecha de cierre (ver
    // sinCambios), salvo que la copia tenga mas de estas horas.
    @Value("${licitacion-service.licitaciones.horas-max-sin-refrescar:168}")
    private long horasMaxSinRefrescar;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LicitacionService.class);

    public LicitacionService(LicitacionClient licitacionClient, LicitacionRepository licitacionRepository,
            LicitacionMapper licitacionMapper, PerfilClient perfilClient, CatalogoService catalogoService) {
        this.licitacionClient = licitacionClient;
        this.licitacionRepository = licitacionRepository;
        this.licitacionMapper = licitacionMapper;
        this.perfilClient = perfilClient;
        this.catalogoService = catalogoService;
    }

    // Busqueda por codigo puntual -- ya no se acota por asignaciones (ver
    // listarUltimosDiasCacheado): el catalogo completo de Mercado Publico
    // es visible para cualquier usuario autenticado, asignaciones ahora
    // solo trackea el trabajo propio ("Mis activas" en el front), no la
    // visibilidad del catalogo.
    //
    // 100% CACHE, nunca en vivo (mismo criterio que CompraAgilService.
    // getDetalleByCodigo, decision 2026-09-23: la base tiene que sostener
    // el sistema aunque Mercado Publico este caido/lento -- antes esto le
    // pegaba en vivo como respaldo cuando no encontraba nada cacheado, y
    // ese respaldo era la fuente de los timeouts). licitacionRepository
    // solo guarda detalle COMPLETO (el listado por fecha, resumido, nunca
    // se cachea -- ver comentario en getLicitacionesPorFecha), asi que
    // estar presente ya alcanza, no hace falta chequear "frescura" aca (a
    // diferencia de obtenerDetalleSeguro, que sigue usando estaFresca()
    // para decidir si vale la pena pedirla de nuevo durante el sync).
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
    //
    // 2026-10-06: antes se pedian los diasListado+1 dias de listado (16
    // llamados) en CADA ciclo. Ahora la ventana completa corre al arrancar y
    // cada HORAS_ENTRE_VENTANAS_COMPLETAS; el resto de los ciclos pide solo
    // ayer y hoy (los unicos dias donde aparece algo nuevo entre un ciclo y
    // otro).
    private static final long HORAS_ENTRE_VENTANAS_COMPLETAS = 6;
    private volatile java.time.Instant ultimaVentanaCompleta;

    public LicitacionResponse sincronizarUltimosDias() {
        java.time.Instant inicio = java.time.Instant.now();
        boolean completa = ultimaVentanaCompleta == null
                || ultimaVentanaCompleta.isBefore(inicio.minus(java.time.Duration.ofHours(HORAS_ENTRE_VENTANAS_COMPLETAS)));
        ZonedDateTime ahora = ZonedDateTime.now(ZONA_CHILE);
        ZonedDateTime desde = ahora.minusDays(completa ? diasListado : 1);

        // LinkedHashMap: la misma licitacion puede venir en mas de un dia.
        LinkedHashMap<String, Licitacion> candidatas = new LinkedHashMap<>();
        LicitacionResponse ultimaRespuesta = null;
        int diasFallidos = 0;

        LocalDate dia = desde.toLocalDate();
        while (!dia.isAfter(ahora.toLocalDate())) {
            try {
                LicitacionResponse respuesta = licitacionClient.getLicitacionesPorFecha(dia.format(FORMATO_FECHA_API));
                if (respuesta != null) {
                    ultimaRespuesta = respuesta;
                    if (respuesta.listado() != null) {
                        for (Licitacion licitacion : respuesta.listado()) {
                            if (licitacion.codigoExterno() != null) {
                                candidatas.putIfAbsent(licitacion.codigoExterno(), licitacion);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Si falla el listado de un dia puntual no debe tumbar el resto:
                // se sigue con los demas dias del rango.
                diasFallidos++;
                log.warn("Listado de licitaciones del {} FALLÓ: {}", dia, e.getMessage());
            }
            dia = dia.plusDays(1);
        }
        if (completa && diasFallidos == 0) {
            ultimaVentanaCompleta = inicio;
        }

        // Pool propio y acotado (paralelismoDetalle, antes 8 fijos): la API
        // v1 rechaza peticiones simultaneas del mismo ticket y comparte la
        // cuota diaria.
        java.util.concurrent.atomic.AtomicInteger fallidas = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger pedidas = new java.util.concurrent.atomic.AtomicInteger();
        List<Licitacion> filtradas;
        ForkJoinPool poolAcotado = new ForkJoinPool(Math.max(1, paralelismoDetalle));
        try {
            filtradas = poolAcotado.submit(() -> candidatas.values().parallelStream()
                    .map(resumen -> obtenerDetalleSeguro(resumen, pedidas, fallidas))
                    .filter(Objects::nonNull)
                    .filter(lic -> estaDentroDelRango(lic, desde, ahora))
                    .toList()).join();
        } finally {
            poolAcotado.shutdown();
        }
        log.info("Sync licitaciones ({} dia(s)): {} candidatas, {} detalles pedidos a la API, {} fallidos.",
                completa ? diasListado + 1 : 2, candidatas.size(), pedidas.get(), fallidas.get());

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
    public LicitacionResponse listarUltimosDiasCacheado(AuthenticatedPrincipal principal, String authorizationHeader, int pagina, int tamano, FiltrosVista filtros) {
        LocalDateTime desde = ZonedDateTime.now(ZONA_CHILE).minusDays(diasListado).toLocalDateTime();
        List<Licitacion> listado = aplicarFiltros(licitacionRepository.findByFechaPublicacionDesde(desde), filtros).stream()
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
    public LicitacionResponse buscarConFiltroEmpresa(AuthenticatedPrincipal principal, String authorizationHeader, int pagina, int tamano, FiltrosVista filtros) {
        if (principal == null || principal.alcance() == Alcance.GLOBAL) {
            return listarUltimosDiasCacheado(principal, authorizationHeader, pagina, tamano, filtros);
        }

        List<PerfilBusquedaDto> perfiles = obtenerPerfiles(authorizationHeader);
        if (perfiles.isEmpty()) {
            return new LicitacionResponse(0, null, null, List.of());
        }

        LocalDateTime desde = ZonedDateTime.now(ZONA_CHILE).minusDays(diasListado).toLocalDateTime();
        List<LicitacionEntity> cacheadas = licitacionRepository.findByFechaPublicacionDesde(desde);

        // Texto normalizado una sola vez por licitacion (no una vez por filtro):
        // nombre + descripcion + sus items (producto, descripcion y categoria,
        // que es la clasificacion de rubro de MP). Los items ya vienen cargados
        // (JOIN FETCH en findByFechaPublicacionDesde).
        // El texto (armar + normalizar nombre, descripcion e items) solo hace
        // falta si algun filtro sigue por palabras clave (2026-10-16).
        boolean hayFiltrosPorPalabras = perfiles.stream().anyMatch(p -> !CatalogoService.tieneSeleccion(p.catalogoJson()));
        Map<String, String> textoPorCodigo = new HashMap<>();
        Map<String, Set<String>> codigosPorLicitacion = new HashMap<>();
        for (LicitacionEntity licitacion : cacheadas) {
            Set<String> codigosItems = new HashSet<>();
            for (LicitacionItemEntity item : licitacion.getItems()) {
                if (item.getCodigoProducto() != null) {
                    codigosItems.add(item.getCodigoProducto().toString());
                }
            }
            codigosPorLicitacion.put(licitacion.getCodigoExterno(), codigosItems);
            if (!hayFiltrosPorPalabras) {
                continue;
            }
            StringBuilder texto = new StringBuilder()
                    .append(licitacion.getNombre() != null ? licitacion.getNombre() : "").append(' ')
                    .append(licitacion.getDescripcion() != null ? licitacion.getDescripcion() : "");
            for (LicitacionItemEntity item : licitacion.getItems()) {
                for (String parte : new String[] { item.getNombreProducto(), item.getDescripcion(), item.getCategoria() }) {
                    if (parte != null) {
                        texto.append(' ').append(parte);
                    }
                }
            }
            textoPorCodigo.put(licitacion.getCodigoExterno(), FiltroPalabrasClave.normalizar(texto.toString()));
        }

        LinkedHashMap<String, LicitacionEntity> combinadas = new LinkedHashMap<>();
        for (PerfilBusquedaDto perfil : perfiles) {
            // Con seleccion del catalogo: SOLO codigo de producto exacto de sus
            // items (ver CompraAgilService.buscarPorPerfil). Sin seleccion:
            // rubro + palabras clave.
            boolean porCatalogo = CatalogoService.tieneSeleccion(perfil.catalogoJson());
            Set<String> codigosCatalogo = porCatalogo ? catalogoService.codigosDe(perfil.catalogoJson()) : Set.of();
            List<List<String>> frases = porCatalogo ? List.of()
                    : FiltroPalabrasClave.frasesDelFiltro(perfil.rubro(), perfil.palabrasClave());
            for (LicitacionEntity licitacion : cacheadas) {
                boolean coincide = porCatalogo
                        ? coincideConPerfil(licitacion, null, List.of(), perfil.regionNombre())
                                && !Collections.disjoint(codigosPorLicitacion.get(licitacion.getCodigoExterno()), codigosCatalogo)
                        : coincideConPerfil(licitacion, textoPorCodigo.get(licitacion.getCodigoExterno()), frases,
                                perfil.regionNombre());
                if (coincide) {
                    combinadas.putIfAbsent(licitacion.getCodigoExterno(), licitacion);
                }
            }
        }

        List<Licitacion> listado = aplicarFiltros(List.copyOf(combinadas.values()), filtros).stream()
                .map(licitacionMapper::toDto)
                .toList();
        return paginar(listado, pagina, tamano);
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

    // 2026-10-06: misma regla que Compra Agil (ver FiltroPalabrasClave) --
    // antes se separaba por palabra suelta (con la coma pegada: "electricos,"
    // nunca coincidia) y alcanzaba con UNA palabra cualquiera ("materiales"
    // traia de todo). Region por nombre (regionUnidad es texto libre de
    // Mercado Publico, ej. "Región de Valparaíso"), sin tildes ni mayusculas.
    // El front guarda el nombre con el numero romano adelante ("V -
    // Valparaíso", ver lib/regionesComunas.js): sin sacarle ese prefijo
    // ningun filtro con region coincidia nunca.
    private boolean coincideConPerfil(LicitacionEntity licitacion, String textoNormalizado,
            List<List<String>> frases, String regionNombre) {
        if (!FiltroPalabrasClave.coincide(textoNormalizado, frases)) {
            return false;
        }
        if (regionNombre == null || regionNombre.isBlank()) {
            return true;
        }
        String regionBuscada = FiltroPalabrasClave.normalizar(regionNombre.replaceFirst("^\\s*[A-Za-z]+\\s*-\\s*", ""));
        String regionLicitacion = FiltroPalabrasClave.normalizar(licitacion.getRegionUnidad());
        return !regionLicitacion.isBlank() && regionLicitacion.contains(regionBuscada);
    }

    // Filtros/orden del panel de Licitacion.jsx (ver LicitacionDto.
    // FiltrosVista) -- mismo criterio que CompraAgilService.aplicarFiltros,
    // todo en memoria sobre el universo que ya trajo la consulta base.
    // "region" es un CONTAINS case-insensitive (no exacto): regionUnidad es
    // texto libre de Mercado Publico, no un codigo -- ver comentario del
    // record.
    private List<LicitacionEntity> aplicarFiltros(List<LicitacionEntity> entidades, FiltrosVista filtros) {
        LocalDateTime ahora = FechaParser.ahora();
        // Las que ya cerraron no se muestran en las listas -- mismo pedido
        // que CompraAgilService.aplicarFiltros, solo se ven buscando a mano
        // por codigo (getLicitacionByCodigo no pasa por este metodo). Sin
        // fechaCierre se deja pasar, no hay forma de saber si cerro.
        var stream = entidades.stream()
                .filter(l -> l.getFechaCierre() == null || l.getFechaCierre().isAfter(ahora));

        if (filtros != null) {
            if (filtros.region() != null && !filtros.region().isBlank()) {
                String regionBuscada = FiltroPalabrasClave.normalizar(filtros.region());
                stream = stream.filter(l -> l.getRegionUnidad() != null
                        && FiltroPalabrasClave.normalizar(l.getRegionUnidad()).contains(regionBuscada));
            }
            if (filtros.montoMin() != null) {
                stream = stream.filter(l -> l.getMontoEstimado() != null && l.getMontoEstimado().compareTo(filtros.montoMin()) >= 0);
            }
            if (filtros.montoMax() != null) {
                stream = stream.filter(l -> l.getMontoEstimado() != null && l.getMontoEstimado().compareTo(filtros.montoMax()) <= 0);
            }
            if (filtros.cierreDesde() != null) {
                LocalDateTime desde = filtros.cierreDesde().atStartOfDay();
                stream = stream.filter(l -> l.getFechaCierre() != null && !l.getFechaCierre().isBefore(desde));
            }
            if (filtros.cierreHasta() != null) {
                LocalDateTime hasta = filtros.cierreHasta().plusDays(1).atStartOfDay();
                stream = stream.filter(l -> l.getFechaCierre() != null && l.getFechaCierre().isBefore(hasta));
            }
        }

        Comparator<LicitacionEntity> comparador = switch (filtros != null && filtros.ordenarPor() != null ? filtros.ordenarPor() : "cierre") {
            case "publicacion" -> Comparator.comparing(LicitacionEntity::getFechaPublicacion, Comparator.nullsLast(Comparator.naturalOrder()));
            case "monto" -> Comparator.comparing(LicitacionEntity::getMontoEstimado, Comparator.nullsLast(Comparator.naturalOrder()));
            case "nombre" -> Comparator.comparing(LicitacionEntity::getNombre, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            default -> Comparator.comparing(LicitacionEntity::getFechaCierre, Comparator.nullsLast(Comparator.naturalOrder()));
        };
        if (filtros != null && "desc".equalsIgnoreCase(filtros.direccion())) {
            comparador = comparador.reversed();
        }

        return stream.sorted(comparador).toList();
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
    //
    // 2026-10-06: tambien se usa la cache si el listado dice que no cambio
    // ni el estado ni la fecha de cierre (sinCambios) -- antes se volvia a
    // pedir el detalle de TODAS las candidatas cada vez que vencia el TTL,
    // lo que agotaba la cuota diaria. Si la API falla, se usa la copia
    // cacheada si existe (antes se descartaba la licitacion).
    private Licitacion obtenerDetalleSeguro(Licitacion resumen,
            java.util.concurrent.atomic.AtomicInteger pedidas,
            java.util.concurrent.atomic.AtomicInteger fallidas) {
        // Todo el metodo va dentro de un unico try/catch: corre en hilos del
        // ForkJoinPool (parallelStream), asi que cualquier falla puntual (DB,
        // API externa, mapeo) no debe tumbar el resto del listado.
        Optional<LicitacionEntity> cacheada = Optional.empty();
        try {
            cacheada = licitacionRepository.findByIdConItems(resumen.codigoExterno());
            if (cacheada.isPresent() && (estaFresca(cacheada.get()) || sinCambios(cacheada.get(), resumen))) {
                return licitacionMapper.toDto(cacheada.get());
            }

            pedidas.incrementAndGet();
            LicitacionResponse detalle = licitacionClient.getLicitacionByCodigo(resumen.codigoExterno());
            if (detalle != null && detalle.listado() != null && !detalle.listado().isEmpty()) {
                Licitacion licitacion = detalle.listado().get(0);
                guardarEnCache(licitacion);
                return licitacion;
            }
            fallidas.incrementAndGet();
        } catch (Exception e) {
            fallidas.incrementAndGet();
            log.debug("Detalle de licitacion {} FALLÓ: {}", resumen.codigoExterno(), e.getMessage());
        }
        try {
            return cacheada.map(licitacionMapper::toDto).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    // El listado por fecha (resumido) SI trae CodigoEstado y FechaCierre: si
    // ninguno cambio respecto a lo cacheado, y la copia no es demasiado
    // vieja, no hace falta pedir de nuevo el detalle completo.
    private boolean sinCambios(LicitacionEntity entity, Licitacion resumen) {
        LocalDateTime fechaSync = entity.getFechaSync();
        if (fechaSync == null || fechaSync.isBefore(LocalDateTime.now().minusHours(horasMaxSinRefrescar))) {
            return false;
        }
        if (resumen.codigoEstado() == null || !resumen.codigoEstado().equals(entity.getCodigoEstado())) {
            return false;
        }
        LocalDateTime cierreResumen = FechaParser.parse(resumen.fechaCierre());
        return cierreResumen == null || cierreResumen.equals(entity.getFechaCierre());
    }

    private void guardarEnCache(Licitacion licitacion) {
        try {
            licitacionRepository.save(licitacionMapper.toEntity(licitacion, LocalDateTime.now()));
        } catch (Exception e) {
            // Un fallo al escribir en la cache no tumba el sync, pero queda en el log.
            log.warn("No se pudo cachear la licitacion {}: {}", licitacion.codigoExterno(), e.getMessage());
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
            // Misma convencion que FechaParser: la hora escrita es la de Chile,
            // aunque venga con "Z".
            return OffsetDateTime.parse(fecha).toLocalDateTime().atZone(ZONA_CHILE);
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
