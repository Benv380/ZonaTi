package cl.zona_ti.compra_service.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import cl.zona_ti.compra_service.Client.CompraAgilClient;
import cl.zona_ti.compra_service.Client.PerfilClient;
import cl.zona_ti.compra_service.Client.ReclamosClient;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.CompraAgilDetalleResponse;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.CompraAgilError;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.CompraAgilListadoResponse;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.Detalle;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.FiltrosVista;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.Item;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.Listado;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.Paginacion;
import cl.zona_ti.compra_service.Dto.PerfilBusquedaDto;
import cl.zona_ti.compra_service.Dto.PerfilCompradorResponse;
import cl.zona_ti.compra_service.Dto.PerfilCompradorResponse.ConteoMensual;
import cl.zona_ti.compra_service.Dto.PerfilCompradorResponse.ConteoTexto;
import cl.zona_ti.compra_service.Dto.PerfilCompradorResponse.ReclamoPorTipo;
import cl.zona_ti.compra_service.Dto.PerfilCompradorResponse.ReclamosResumen;
import cl.zona_ti.compra_service.Dto.PerfilVendedorResponse;
import cl.zona_ti.compra_service.Dto.ReclamosDto.CalculosReclamosResponse;
import cl.zona_ti.compra_service.Mapper.CompraAgilMapper;
import cl.zona_ti.compra_service.Model.Alcance;
import cl.zona_ti.compra_service.Model.CompraAgilEntity;
import cl.zona_ti.compra_service.Model.CompraAgilProveedorCotizandoEntity;
import cl.zona_ti.compra_service.Repository.CompraAgilProveedorCotizandoRepository;
import cl.zona_ti.compra_service.Repository.CompraAgilRepository;
import cl.zona_ti.compra_service.Security.AuthenticatedPrincipal;
import cl.zona_ti.compra_service.Util.FechaParser;
import cl.zona_ti.compra_service.Util.FiltroPalabrasClave;

@Service
public class CompraAgilService {

    // Codigos por consulta en textoProductos (ver buscarPorPerfil).
    private static final int TAMANO_LOTE_PRODUCTOS = 1000;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CompraAgilService.class);

    private final CompraAgilClient compraAgilClient;
    private final CompraAgilRepository compraAgilRepository;
    private final CompraAgilProveedorCotizandoRepository compraAgilProveedorCotizandoRepository;
    private final CompraAgilMapper compraAgilMapper;
    private final PerfilClient perfilClient;
    private final ReclamosClient reclamosClient;
    // Leer + modificar + guardar una CompraAgilEntity tiene que pasar en UNA
    // sola transaccion: fuera de ella (hilos del scheduler, sin
    // open-in-view) findById() devuelve la entidad ya desconectada de la
    // sesion, y el mapper revienta con LazyInitializationException al tocar
    // sus colecciones LAZY (documentos/productos/proveedores). Ese error se
    // tragaba en silencio: las compras YA cacheadas nunca se actualizaban
    // (ni el detalle completo, ni los cambios de estado) -- solo se
    // guardaban bien la primera vez, cuando todavia no existian.
    private final TransactionTemplate transactionTemplate;

    // Tamaño de pagina por defecto para /listar -- ver
    // listarUltimasOchoHorasCacheado().
    private static final int TAMANO_PAGINA_DEFECTO = 15;

    public CompraAgilService(CompraAgilClient compraAgilClient, CompraAgilRepository compraAgilRepository,
            CompraAgilProveedorCotizandoRepository compraAgilProveedorCotizandoRepository,
            CompraAgilMapper compraAgilMapper, PerfilClient perfilClient, ReclamosClient reclamosClient,
            PlatformTransactionManager transactionManager) {
        this.compraAgilClient = compraAgilClient;
        this.compraAgilRepository = compraAgilRepository;
        this.compraAgilProveedorCotizandoRepository = compraAgilProveedorCotizandoRepository;
        this.compraAgilMapper = compraAgilMapper;
        this.perfilClient = perfilClient;
        this.reclamosClient = reclamosClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // Interno/de confianza -- los "filtros" ya vienen resueltos (por el
    // scheduler con su rango de fechas fijo, o por buscar() con los del
    // perfil de la empresa). Nunca se expone directo con filtros que
    // vengan sueltos del cliente -- eso es lo que hacia antes el endpoint
    // del controller, y era la "puerta sin candado" (ver buscar()).
    // Historiza cada item que llega: sirve para que getDetalleByCodigo() y
    // listarUltimasOchoHoras() tengan de donde cachear.
    private CompraAgilListadoResponse listar(Map<String, String> filtros) {
        CompraAgilListadoResponse respuesta = compraAgilClient.listar(filtros);
        if (respuesta != null && respuesta.payload() != null && respuesta.payload().items() != null) {
            for (Item item : respuesta.payload().items()) {
                guardarItemEnCache(item);
            }
        }
        return respuesta;
    }

    // Puerta 2: busqueda libre expuesta al usuario (CompraAgilController.listar,
    // ej. /compra/agil?q=materiales%20electricos&region=13).
    //
    //   - GLOBAL: usa los filtros que mande el cliente tal cual (confia en el).
    //   - EMPRESA/USUARIO: IGNORA por completo los filtros del cliente --
    //     arma q=/region= a partir del perfil_busqueda de SU empresa (ver
    //     PerfilClient), asi nadie puede salirse del rubro/region que le
    //     configuraron. Si la empresa todavia no tiene el perfil completo
    //     (perfilCompletado=false -- va a pasar SIEMPRE hasta que se
    //     conecte el onboarding con LLM) o auth-service no responde, NO se
    //     pega a Mercado Publico sin filtro (eso traeria TODO) -- se
    //     devuelve vacio directo, mismo criterio "fail closed" que
    //     misCodigos().
    public CompraAgilListadoResponse buscar(Map<String, String> filtrosCliente, AuthenticatedPrincipal principal, String authorizationHeader) {
        if (principal == null || principal.alcance() == Alcance.GLOBAL) {
            return listar(filtrosCliente);
        }

        Map<String, String> filtrosDesdePerfil = filtrosDesdePerfil(authorizationHeader);
        if (filtrosDesdePerfil.isEmpty()) {
            return new CompraAgilListadoResponse("true", null, new Listado(List.of(), new Paginacion(1, 1, 0, 0)), null);
        }
        return listar(filtrosDesdePerfil);
    }

    // "Ver mi filtro" en Compra Agil (ver CompraAgilController) -- antes
    // pegaba EN VIVO a Mercado Publico en cada clic (Puerta 2, buscar()),
    // que es lento (ver conversacion: hasta 20s por la API externa). Ahora
    // busca en el CACHE local ya sincronizado (mismo mecanismo que
    // buscarPorTexto), acotado por el rubro/palabras clave/region de los
    // filtros de la empresa -- rapido, sin pegarle a la API externa.
    //
    //   - GLOBAL (o sin empresa): no tiene filtros propios, no tiene
    //     sentido "mi filtro" -- se le devuelve lo mismo que /listar
    //     (cache completo, sin acotar).
    //   - EMPRESA/USUARIO sin ningun filtro guardado: mismo criterio "fail
    //     closed" que antes -- vacio en vez de mostrar todo.
    //   - Multi-filtro (2026-09-23): una empresa puede tener VARIOS
    //     filtros guardados -- se busca la UNION de todos (una compra
    //     aparece si matchea AL MENOS uno), deduplicando por codigo.
    public CompraAgilListadoResponse buscarPorPerfil(AuthenticatedPrincipal principal, String authorizationHeader, int pagina, int tamano, FiltrosVista filtros) {
        if (principal == null || principal.alcance() == Alcance.GLOBAL) {
            return listarUltimasOchoHorasCacheado(principal, authorizationHeader, pagina, tamano, filtros);
        }

        List<PerfilBusquedaDto> perfiles;
        try {
            perfiles = perfilClient.misPerfiles(authorizationHeader);
        } catch (Exception e) {
            perfiles = List.of();
        }
        if (perfiles.isEmpty()) {
            return new CompraAgilListadoResponse("true", null, new Listado(List.of(), new Paginacion(1, 1, 0, 0)), null);
        }

        // 2026-10-06: antes se mandaba el texto ENTERO de palabras clave como
        // un unico LIKE '%...%' (comas incluidas, distinguiendo tildes): con
        // "materiales electricos, cemento" no encontraba practicamente nada.
        // Ahora: universo = compras abiertas en cache (las cerradas igual se
        // esconden en aplicarFiltros), y cada filtro se evalua con
        // FiltroPalabrasClave (frases separadas por coma, todas las palabras
        // de la frase, sin tildes ni plural) + region por codigo.
        LocalDateTime ahora = FechaParser.ahora();
        List<CompraAgilEntity> abiertas = compraAgilRepository.findAbiertas(ahora, ahora.minusHours(48));

        // Texto normalizado una sola vez por compra (no una vez por filtro):
        // nombre + descripcion + productos solicitados (2026-10-14: antes solo
        // nombre y descripcion, y quedaban fuera compras del rubro cuyo nombre
        // era generico).
        Map<String, String> productosPorCodigo = textoProductos(abiertas);
        Map<String, String> textoPorCodigo = new HashMap<>();
        for (CompraAgilEntity compra : abiertas) {
            textoPorCodigo.put(compra.getCodigo(), FiltroPalabrasClave.normalizar(
                    (compra.getNombre() != null ? compra.getNombre() : "") + " "
                            + (compra.getDescripcion() != null ? compra.getDescripcion() : "") + " "
                            + productosPorCodigo.getOrDefault(compra.getCodigo(), "")));
        }

        // LinkedHashMap: union de todos los filtros sin repetir compras, en
        // el orden de la consulta (fecha de publicacion desc; aplicarFiltros
        // reordena despues segun lo que pida el panel).
        LinkedHashMap<String, CompraAgilEntity> combinadas = new LinkedHashMap<>();
        for (PerfilBusquedaDto perfil : perfiles) {
            List<List<String>> frases = FiltroPalabrasClave.frasesDelFiltro(perfil.rubro(), perfil.palabrasClave());
            Integer region = parseRegion(perfil.regionCodigo());
            for (CompraAgilEntity compra : abiertas) {
                if ((region == null || region.equals(compra.getRegion()))
                        && FiltroPalabrasClave.coincide(textoPorCodigo.get(compra.getCodigo()), frases)) {
                    combinadas.putIfAbsent(compra.getCodigo(), compra);
                }
            }
        }
        return paginarCacheado(List.copyOf(combinadas.values()), pagina, tamano, filtros);
    }

    // Texto de los productos solicitados por compra (nombre + descripcion),
    // consultado de a lotes para no armar un IN gigante.
    private Map<String, String> textoProductos(List<CompraAgilEntity> compras) {
        List<String> codigos = compras.stream().map(CompraAgilEntity::getCodigo).toList();
        Map<String, StringBuilder> textos = new HashMap<>();
        for (int i = 0; i < codigos.size(); i += TAMANO_LOTE_PRODUCTOS) {
            List<String> lote = new ArrayList<>(codigos.subList(i, Math.min(i + TAMANO_LOTE_PRODUCTOS, codigos.size())));
            for (Object[] fila : compraAgilRepository.findTextoProductos(lote)) {
                StringBuilder texto = textos.computeIfAbsent((String) fila[0], codigo -> new StringBuilder());
                for (int j = 1; j < fila.length; j++) {
                    if (fila[j] != null) {
                        texto.append(' ').append(fila[j]);
                    }
                }
            }
        }
        Map<String, String> resultado = new HashMap<>();
        textos.forEach((codigo, texto) -> resultado.put(codigo, texto.toString()));
        return resultado;
    }

    // Panel "perfil del comprador" (pedido 2026-09-25): agrega, del lado
    // nuestro, todo lo que ya esta cacheado de un mismo organismo -- no es
    // data que Mercado Publico entregue ya armada. Se identifica por RUT
    // (no por nombre -- el nombre del organismo puede venir con variaciones
    // de mayusculas/espacios entre una compra y otra, el RUT es estable).
    // "demandas"/"multa_sancion" son lo mas cercano a "reclamos" que hay
    // confirmado y ya cacheado por compra (ver Resumen en CompraAgilDto) --
    // Mercado Publico no expone reclamos categorizados por tipo en esta
    // API, asi que no se puede desglosar mas que eso por ahora.
    public PerfilCompradorResponse perfilComprador(String rutInstitucion) {
        List<CompraAgilEntity> compras = compraAgilRepository.findByRutInstitucion(rutInstitucion);

        String organismo = compras.stream()
                .map(CompraAgilEntity::getOrganismoComprador)
                .filter(nombre -> nombre != null && !nombre.isBlank())
                .findFirst()
                .orElse(null);

        BigDecimal montoTotal = compras.stream()
                .map(CompraAgilService::montoRelevante)
                .filter(monto -> monto != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal montoPromedio = compras.isEmpty()
                ? BigDecimal.ZERO
                : montoTotal.divide(BigDecimal.valueOf(compras.size()), 0, java.math.RoundingMode.HALF_UP);

        long comprasConDemandas = compras.stream()
                .filter(c -> c.getTotalDemandas() != null && c.getTotalDemandas() > 0)
                .count();
        long totalDemandas = compras.stream()
                .mapToLong(c -> c.getTotalDemandas() != null ? c.getTotalDemandas() : 0)
                .sum();
        long comprasConMultaSancion = compras.stream()
                .filter(c -> c.getMultaSancion() != null && c.getMultaSancion().signum() > 0)
                .count();
        BigDecimal multaSancionTotal = compras.stream()
                .map(CompraAgilEntity::getMultaSancion)
                .filter(monto -> monto != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ConteoMensual> comprasPorMes = compras.stream()
                .filter(c -> c.getFechaPublicacion() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        c -> c.getFechaPublicacion().toLocalDate().withDayOfMonth(1).toString(),
                        java.util.TreeMap::new,
                        java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .map(entrada -> new ConteoMensual(entrada.getKey(), entrada.getValue()))
                .toList();

        List<ConteoTexto> convocatoriasFrecuentes = compras.stream()
                .map(CompraAgilEntity::getConvocatoriaDescripcion)
                .filter(texto -> texto != null && !texto.isBlank())
                .collect(java.util.stream.Collectors.groupingBy(texto -> texto, java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(entrada -> new ConteoTexto(entrada.getKey(), entrada.getValue()))
                .toList();

        return new PerfilCompradorResponse(
                rutInstitucion,
                organismo,
                compras.size(),
                montoTotal,
                montoPromedio,
                comprasConDemandas,
                totalDemandas,
                comprasConMultaSancion,
                multaSancionTotal,
                comprasPorMes,
                convocatoriasFrecuentes,
                reclamosDe(rutInstitucion));
    }

    // Reclamos reales de ChileCompra (ver ReclamosClient) -- fail closed a
    // proposito: null si el token no esta configurado/vencido o la llamada
    // falla por cualquier motivo (API no oficial, puede cambiar sin aviso).
    // El resto del panel no depende de esto.
    private ReclamosResumen reclamosDe(String rutInstitucion) {
        if (!reclamosClient.estaDisponible()) {
            return null;
        }
        try {
            CalculosReclamosResponse respuesta = reclamosClient.calculosReclamos(rutInstitucion);
            if (respuesta == null || !"OK".equals(respuesta.success()) || respuesta.payload() == null) {
                log.warn("Reclamos: respuesta no OK para rut {} -- revisar si el token vencio.", rutInstitucion);
                return null;
            }
            List<ReclamoPorTipo> porTipo = respuesta.payload().data() == null
                    ? List.of()
                    : respuesta.payload().data().stream()
                            .map(item -> new ReclamoPorTipo(
                                    item.nombre(),
                                    item.cantidadReclamos() != null ? item.cantidadReclamos() : 0,
                                    item.porcentajeReclamo() != null ? item.porcentajeReclamo() : 0))
                            .toList();
            int total = respuesta.payload().totalReclamos() != null ? respuesta.payload().totalReclamos() : 0;
            return new ReclamosResumen(total, porTipo);
        } catch (Exception e) {
            log.warn("Reclamos: no se pudo consultar para rut {}: {}", rutInstitucion, e.getMessage());
            return null;
        }
    }

    // Panel "Perfil del ganador" (pedido 2026-09-25, Home.jsx -- "Compras
    // Ágiles listas"): agrega todas las cotizaciones de un mismo proveedor
    // (por RUT) a lo largo de las compras agiles ya cacheadas CON DETALLE
    // COMPLETO. "proveedor_seleccionado == 1" es el mismo criterio que ya
    // usa ganadorDe() en CompraAgilResueltas.jsx del front.
    public PerfilVendedorResponse perfilVendedor(String rutProveedor) {
        List<CompraAgilProveedorCotizandoEntity> cotizaciones = compraAgilProveedorCotizandoRepository
                .findByRutProveedor(rutProveedor);

        String razonSocial = cotizaciones.stream()
                .map(CompraAgilProveedorCotizandoEntity::getRazonSocial)
                .filter(nombre -> nombre != null && !nombre.isBlank())
                .findFirst()
                .orElse(null);

        List<CompraAgilProveedorCotizandoEntity> ganadas = cotizaciones.stream()
                .filter(c -> c.getProveedorSeleccionado() != null && c.getProveedorSeleccionado() == 1)
                .toList();

        double tasaAdjudicacion = cotizaciones.isEmpty()
                ? 0
                : (ganadas.size() * 100.0) / cotizaciones.size();

        BigDecimal montoTotalGanado = ganadas.stream()
                .map(CompraAgilProveedorCotizandoEntity::getMontoTotal)
                .filter(monto -> monto != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal montoPromedioGanado = ganadas.isEmpty()
                ? BigDecimal.ZERO
                : montoTotalGanado.divide(BigDecimal.valueOf(ganadas.size()), 0, java.math.RoundingMode.HALF_UP);

        List<PerfilVendedorResponse.ConteoTexto> organismosFrecuentes = cotizaciones.stream()
                .map(c -> c.getCompraAgil() != null ? c.getCompraAgil().getOrganismoComprador() : null)
                .filter(texto -> texto != null && !texto.isBlank())
                .collect(java.util.stream.Collectors.groupingBy(texto -> texto, java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(entrada -> new PerfilVendedorResponse.ConteoTexto(entrada.getKey(), entrada.getValue()))
                .toList();

        return new PerfilVendedorResponse(
                rutProveedor,
                razonSocial,
                cotizaciones.size(),
                ganadas.size(),
                tasaAdjudicacion,
                montoPromedioGanado,
                montoTotalGanado,
                organismosFrecuentes);
    }

    // CLP cuando esta disponible (el dato mas directamente comparable entre
    // compras); si no, el monto tal cual venga -- mismo criterio de fallback
    // que ya usa CompraCard.jsx en el front (ver formatearMonto alli).
    private static BigDecimal montoRelevante(CompraAgilEntity compra) {
        if (compra.getMontoDisponibleClp() != null) {
            return compra.getMontoDisponibleClp();
        }
        return compra.getMontoDisponible();
    }

    private Integer parseRegion(String regionCodigo) {
        if (regionCodigo == null || regionCodigo.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(regionCodigo.trim());
        } catch (NumberFormatException ignored) {
            // Codigo de region invalido/no numerico -- se ignora el filtro
            // de region en vez de tirar error, mismo criterio "no bloquear
            // al usuario por un dato mal cargado" que el resto del service.
            return null;
        }
    }

    // Usado SOLO por buscar() (Puerta 2, en vivo contra Mercado Publico) --
    // desde que "Ver mi filtro" se movio al cache (buscarPorPerfil, arriba)
    // esta Puerta 2 quedo secundaria para EMPRESA/USUARIO (el front ya no
    // la llama para ese rol). Mercado Publico solo acepta UN q=/region= por
    // llamada -- con multi-filtro no hay forma de mandarle la union de
    // varios en una sola request externa, asi que se usa el PRIMER filtro
    // guardado nomas (no todos). Si esto se vuelve a necesitar de verdad
    // para varios filtros, la alternativa es una llamada a Mercado Publico
    // por filtro y mezclar del lado nuestro -- no se hizo por ahora porque
    // no hay ningun caso de uso real que dependa de esto.
    private Map<String, String> filtrosDesdePerfil(String authorizationHeader) {
        try {
            List<PerfilBusquedaDto> perfiles = perfilClient.misPerfiles(authorizationHeader);
            if (perfiles.isEmpty()) {
                return Map.of();
            }
            PerfilBusquedaDto perfil = perfiles.get(0);
            Map<String, String> filtros = new HashMap<>();
            if (perfil.palabrasClave() != null && !perfil.palabrasClave().isBlank()) {
                filtros.put("q", perfil.palabrasClave());
            }
            if (perfil.regionCodigo() != null && !perfil.regionCodigo().isBlank()) {
                filtros.put("region", perfil.regionCodigo());
            }
            return filtros;
        } catch (Exception e) {
            return Map.of();
        }
    }

    // Uso EXCLUSIVO de CompraAgilSyncScheduler (corre en background, sin
    // ningun usuario/request de por medio) -- esta es la UNICA via que le
    // pega en vivo a Mercado Publico para el detalle, es literalmente lo
    // que alimenta la cache. No confundir con getDetalleByCodigo() de
    // abajo (esa es 100% cache, la usa el usuario).
    //
    // Lanza excepcion si la API no responde success="OK" con payload (ver
    // doc, seccion 7: en error responde success="NOK", payload=null, y el
    // RestClient no lanza nada por status) o si no se pudo guardar en cache
    // -- antes ambos casos retornaban "normal" y el scheduler los contaba
    // como EXITO, con el panel de monitoreo en verde mientras fallaba.
    public CompraAgilDetalleResponse sincronizarDetalle(String codigo) {
        CompraAgilDetalleResponse respuesta = compraAgilClient.getDetalleByCodigo(codigo);
        if (respuesta == null || !"OK".equalsIgnoreCase(respuesta.success()) || respuesta.payload() == null) {
            throw new IllegalStateException("Detalle de " + codigo + " no OK: "
                    + describirError(respuesta == null ? null : respuesta.success(),
                            respuesta == null ? null : respuesta.errors()));
        }
        guardarDetalleEnCache(respuesta.payload());
        return respuesta;
    }

    private static String describirError(String success, List<CompraAgilError> errores) {
        if (errores != null && !errores.isEmpty()) {
            return errores.stream()
                    .map(err -> (err.codigo() != null ? err.codigo() + " " : "") + (err.mensaje() != null ? err.mensaje() : ""))
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("success=" + success);
        }
        return "success=" + success;
    }

    // Busqueda por codigo puntual -- ya no se acota por asignaciones (ver
    // listarUltimasOchoHorasCacheado): el catalogo completo es visible
    // para cualquier usuario autenticado.
    //
    // 100% CACHE, nunca en vivo (decision 2026-09-23: la base tiene que
    // sostener el sistema aunque Mercado Publico este caido/lento -- antes
    // esto le pegaba en vivo a la API externa como respaldo cuando no
    // encontraba nada cacheado, y ese respaldo era justo la fuente de los
    // timeouts que hacian sentir "lenta" la pagina cuando Mercado Publico
    // tardaba).
    //
    // Ya NO exige "detalleCompleto" (se probo exigirlo, se revirtio el
    // mismo dia -- ver comentario largo en CompraAgilRepository): alcanza
    // con que la fila exista en cache, tenga o no el detalle 100%
    // completo -- el resumen del listado ya trae datos reales y usables.
    // Si el codigo NUNCA se cacheo (ni siquiera el resumen), ahi si se
    // devuelve el error de abajo -- CompraAgilSyncScheduler (ver
    // sincronizarDetalle arriba) es quien la va a traer en su proximo
    // ciclo si es una compra real reciente.
    public CompraAgilDetalleResponse getDetalleByCodigo(String codigo, AuthenticatedPrincipal principal, String authorizationHeader) {
        Optional<CompraAgilEntity> cacheada = compraAgilRepository.findById(codigo);
        if (cacheada.isPresent()) {
            return new CompraAgilDetalleResponse("true", null, compraAgilMapper.toDetalleDto(cacheada.get()), null);
        }

        // Mismo criterio de siempre: si el codigo tal cual no matchea, se
        // busca en cache un codigo cuya version normalizada (sin simbolos,
        // en mayusculas) coincida EXACTO -- tolera mayuscula/minuscula,
        // espacios y guiones de mas/de menos, pero nunca "adivina" un
        // digito -- solo compara contra codigos REALES ya sincronizados.
        String codigoReal = buscarCodigoNormalizado(codigo);
        if (codigoReal != null) {
            Optional<CompraAgilEntity> porNormalizado = compraAgilRepository.findById(codigoReal);
            if (porNormalizado.isPresent()) {
                return new CompraAgilDetalleResponse("true", null, compraAgilMapper.toDetalleDto(porNormalizado.get()), null);
            }
        }

        return new CompraAgilDetalleResponse("false", null, null, List.of(
                new CompraAgilError("cache-pendiente",
                        "Todavía no se sincronizó esta compra ágil. El sistema la sincroniza automáticamente cada hora, inténtelo nuevamente más tarde.",
                        null)));
    }

    private static String normalizarParaComparar(String valor) {
        return valor == null ? "" : valor.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
    }

    private String buscarCodigoNormalizado(String entrada) {
        String entradaNormalizada = normalizarParaComparar(entrada);
        if (entradaNormalizada.isBlank()) {
            return null;
        }
        LocalDateTime desde = FechaParser.ahora().minusHours(48);
        return compraAgilRepository.findByFechaPublicacionDesde(desde).stream()
                .map(CompraAgilEntity::getCodigo)
                .filter(codigoCacheado -> normalizarParaComparar(codigoCacheado).equals(entradaNormalizada))
                .findFirst()
                .orElse(null);
    }

    // Golpea la API externa (publicado_desde/publicado_hasta) y deja cacheado
    // cada item -- pesado, exclusivo para que CompraAgilSyncScheduler lo corra
    // en background. El usuario lee el resultado ya cacheado via
    // listarUltimasOchoHorasCacheado() (mismo fix que se hizo para
    // licitaciones: no llamar esto desde un endpoint que responde a un
    // request, corre el riesgo de tardar mas que el timeout del proxy).
    //
    // 2026-10-06: antes pedia UNA sola pagina sin tamano_pagina (la API
    // devuelve 15 por defecto) -- de los cientos de compras publicadas en
    // 48h solo entraban 15 por ciclo, el resto nunca se cacheaba. Ahora,
    // pensado para gastar lo minimo de cuota en llamados redundantes:
    //   - recorre TODAS las paginas, con la misma consulta por fecha de
    //     publicacion que ya funcionaba;
    //   - ventana INCREMENTAL: cada ciclo pide solo desde donde termino el
    //     ultimo listado completo que salio bien (ultimoListadoHasta), con
    //     MINUTOS_SOLAPE de margen por si Mercado Publico publica con algo de
    //     atraso. Con un ciclo por hora son ~2h de listado. Si un ciclo
    //     falla, la marca no avanza y el siguiente cubre el hueco solo;
    //   - ventana COMPLETA (48h) solo al arrancar y cada
    //     HORAS_ENTRE_VENTANAS_COMPLETAS: re-lee compras mas viejas para
    //     detectar cambios (cancelada, desierta...) en las que siguen
    //     abiertas;
    //   - devuelve SOLO los codigos que necesitan pedir el detalle (nuevos,
    //     sin detalle completo, o que cambiaron segun fecha_ultimo_cambio,
    //     ver guardarItemEnCache) -- las que ya estaban y no cambiaron no
    //     gastan un llamado de detalle. En la BD no se duplica nada: la
    //     fila se identifica por el codigo de la compra.
    private static final long HORAS_VENTANA_COMPLETA = 48;
    private static final long HORAS_ENTRE_VENTANAS_COMPLETAS = 24;
    private static final long MINUTOS_SOLAPE = 60;
    // "Hasta" del ultimo listado que se leyo completo y bien, en hora de
    // Chile (en memoria: al reiniciar se arranca con la ventana completa).
    //
    // Por que hora de Chile: Mercado Publico guarda y devuelve sus fechas en
    // hora de Chile aunque les ponga "Z" (ver FechaParser), y lo mas
    // probable es que tambien compare publicado_desde/hasta asi. El "desde"
    // se arma con la hora de Chile (+"Z") y el "hasta" con la hora UTC real
    // (que es mas tarde): si la API los toma como hora de Chile la ventana
    // es exacta, y si los toma como UTC real queda ~3h mas ancha (relee
    // algo de mas, pero no se pierde nada). Con un "desde" en UTC, y la API
    // comparando en hora de Chile, se habrian salteado las ultimas ~3h.
    private volatile LocalDateTime ultimoListadoHasta;
    private static final DateTimeFormatter FORMATO_PARAM_API = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
    // Paginas de 15 (el tamano por defecto de la API). Bug real 2026-10-06:
    // con tamano_pagina=50 la API tardaba mas de ~29s y el gateway delante
    // de ella cortaba con una respuesta sin "success" (success=null), tanto
    // en el listado como en tramos chicos de 6h; con 10/15 por pagina (lo
    // que usaba la version que funcionaba, y un curl manual) responde bien.
    private static final String TAMANO_PAGINA_LISTADO = "15";
    // Tope de seguridad (48h rara vez pasa de ~100 paginas de 15).
    private static final int MAX_PAGINAS_LISTADO = 200;
    private volatile java.time.Instant ultimaVentanaCompleta;

    public List<String> sincronizarPublicadasRecientes() {
        java.time.Instant inicio = java.time.Instant.now();
        LocalDateTime ahoraChile = FechaParser.ahora().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        LocalDateTime topeInferior = ahoraChile.minusHours(HORAS_VENTANA_COMPLETA);

        boolean completa = ultimaVentanaCompleta == null
                || ultimaVentanaCompleta.isBefore(inicio.minus(java.time.Duration.ofHours(HORAS_ENTRE_VENTANAS_COMPLETAS)));
        if (completa) {
            try {
                return listarVentana(topeInferior, ahoraChile, true, inicio);
            } catch (RuntimeException e) {
                // Si ni la primera pagina de 48h sale, al menos que entre lo
                // reciente en este ciclo; la ventana completa se reintenta en
                // el proximo.
                log.warn("Listado compra agil (48h) fallo: {} -- se intenta solo con lo reciente.", e.getMessage());
            }
        }

        LocalDateTime desde = ultimoListadoHasta != null
                ? ultimoListadoHasta.minusMinutes(MINUTOS_SOLAPE)
                : ahoraChile.minusMinutes(2 * MINUTOS_SOLAPE);
        if (desde.isBefore(topeInferior)) {
            desde = topeInferior;
        }
        return listarVentana(desde, ahoraChile, false, inicio);
    }

    // desde/hastaChile en hora de Chile (ver ultimoListadoHasta).
    private List<String> listarVentana(LocalDateTime desde, LocalDateTime hastaChile, boolean completa,
            java.time.Instant inicio) {
        long minutos = java.time.Duration.between(desde, hastaChile).toMinutes();

        Map<String, String> filtrosBase = new HashMap<>();
        filtrosBase.put("publicado_desde", desde.format(FORMATO_PARAM_API));
        // "hasta" con la hora UTC real (mas tarde que la de Chile): cubre
        // las 2 formas en que la API pueda interpretar el parametro.
        filtrosBase.put("publicado_hasta", OffsetDateTime.now(ZoneOffset.UTC)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_INSTANT));
        filtrosBase.put("tamano_pagina", TAMANO_PAGINA_LISTADO);

        java.util.LinkedHashSet<String> necesitanDetalle = new java.util.LinkedHashSet<>();
        int vistas = 0;
        int pagina = 1;
        int totalPaginas = 1;
        boolean todasLasPaginas = true;
        do {
            Map<String, String> filtros = new HashMap<>(filtrosBase);
            filtros.put("numero_pagina", String.valueOf(pagina));
            CompraAgilClient.RespuestaListado respuesta = listarPaginaConReintento(filtros, pagina);
            if (!respuesta.esOk()) {
                if (pagina == 1) {
                    throw new IllegalStateException("Listado compra agil no OK: " + respuesta.describir());
                }
                // Lo de las paginas anteriores ya quedo cacheado: se usa igual.
                log.warn("Listado compra agil: pagina {} de {} no OK ({}) -- se sigue con lo ya leido.",
                        pagina, totalPaginas, respuesta.describir());
                todasLasPaginas = false;
                break;
            }
            CompraAgilListadoResponse cuerpo = respuesta.cuerpo();
            if (cuerpo.payload() == null || cuerpo.payload().items() == null) {
                break;
            }
            for (Item item : cuerpo.payload().items()) {
                vistas++;
                if (guardarItemEnCache(item)) {
                    necesitanDetalle.add(item.codigo());
                }
            }
            Paginacion paginacion = cuerpo.payload().paginacion();
            totalPaginas = paginacion != null && paginacion.total_paginas() != null ? paginacion.total_paginas() : 1;
            pagina++;
        } while (pagina <= totalPaginas && pagina <= MAX_PAGINAS_LISTADO);

        // La marca solo avanza si se leyeron TODAS las paginas: si quedo algo
        // a medias, el proximo ciclo vuelve a cubrir esta ventana.
        if (todasLasPaginas) {
            ultimoListadoHasta = hastaChile;
            if (completa) {
                ultimaVentanaCompleta = inicio;
            }
        }
        log.info("Listado compra agil ({}, {}h{}m): {} compras en {} pagina(s), {} necesitan detalle.",
                completa ? "completo" : "incremental", minutos / 60, minutos % 60,
                vistas, pagina - 1, necesitanDetalle.size());
        return new java.util.ArrayList<>(necesitanDetalle);
    }

    // Un corte puntual (timeout/gateway: sin envelope, o error de red) se
    // reintenta UNA vez tras una pausa corta. Un NOK de la API (400, 429...)
    // no: volveria a fallar igual y gastaria cuota.
    private CompraAgilClient.RespuestaListado listarPaginaConReintento(Map<String, String> filtros, int pagina) {
        CompraAgilClient.RespuestaListado respuesta;
        try {
            respuesta = compraAgilClient.listarConEstado(filtros);
            boolean esNokDeLaApi = respuesta.cuerpo() != null && respuesta.cuerpo().success() != null;
            if (respuesta.esOk() || esNokDeLaApi) {
                return respuesta;
            }
            log.warn("Listado compra agil: pagina {} sin respuesta valida ({}) -- se reintenta una vez.",
                    pagina, respuesta.describir());
        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.warn("Listado compra agil: pagina {} {} -- se reintenta una vez.", pagina, e.getMessage());
        }
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return compraAgilClient.listarConEstado(filtros);
    }

    // Uso EXCLUSIVO de CompraAgilSyncScheduler -- complementa
    // sincronizarPublicadasRecientes() de arriba (que solo trae compras
    // publicadas en las ultimas 48h). Una compra con un cierre lejano (2do
    // llamado, plazos largos) sale de esa ventana de publicacion mucho
    // antes de cerrar de verdad, y sin esto quedaba con su estado/fecha de
    // cierre congelados para siempre. Ventana acotada a proposito (ver
    // CompraAgilRepository.findCodigosProximosACerrar) para no multiplicar
    // llamados en vivo a Mercado Publico re-chequeando compras que igual
    // van a seguir abiertas por dias.
    //
    // 2026-10-06: ahora solo las que YA pasaron su hora de cierre en las
    // ultimas HORAS_ATRAS_RESYNC y siguen "publicada" en cache (ver
    // CompraAgilRepository.findCodigosProximosACerrar) -- antes se
    // re-pedian ~400 detalles en cada ciclo. "ahora" en hora de Chile: las
    // fechas de cierre de Mercado Publico se guardan con la hora de Chile
    // tal cual vienen (ver FechaParser), y el contenedor corre en UTC.
    private static final long HORAS_ATRAS_RESYNC = 12;

    public List<String> codigosProximosACerrar() {
        LocalDateTime ahoraChile = FechaParser.ahora();
        return compraAgilRepository.findCodigosProximosACerrar(
                ahoraChile.minusHours(HORAS_ATRAS_RESYNC), ahoraChile);
    }

    // Uso EXCLUSIVO del panel de monitoreo (ver SyncController.salud()).
    // Bug real detectado 2026-10-02: esto antes pegaba EN VIVO a Mercado
    // Publico en cada request, y el panel se auto-refresca cada 15s --
    // solo esa pestaña abierta generaba ~5700 llamados/dia contra el
    // ticket, sumado a toda la sincronizacion normal, y saturo la cuota
    // diaria de la API. Ahora es un cache: un scheduler aparte (abajo)
    // refresca esto cada varios minutos en vez de en cada visita al
    // panel -- pingVivo() queda instantaneo, sin pegarle a nada externo.
    private volatile CompraAgilClient.EstadoApiEnVivo ultimoPingVivo;

    @org.springframework.scheduling.annotation.Scheduled(
            initialDelay = 0,
            fixedDelayString = "${compra-service.monitoreo.ping-delay:PT5M}")
    public void refrescarPingVivo() {
        ultimoPingVivo = compraAgilClient.pingVivo();
    }

    public CompraAgilClient.EstadoApiEnVivo pingVivo() {
        return ultimoPingVivo;
    }

    // Version rapida para servir al usuario: lee directo de lo que
    // CompraAgilSyncScheduler ya sincronizo en background, sin pegarle en
    // vivo a la API externa.
    //
    // Ya NO se acota por asignaciones individuales -- cualquier usuario
    // autenticado ve la cartera completa (mismo criterio que
    // LicitacionService.listarUltimosDiasCacheado). "pagina"/"tamano"
    // paginan la respuesta -- reusa el mismo record Paginacion que ya
    // devuelve Mercado Publico para la busqueda en vivo (Puerta 2), asi
    // el front trata ambas respuestas igual.
    public CompraAgilListadoResponse listarUltimasOchoHorasCacheado(AuthenticatedPrincipal principal, String authorizationHeader, int pagina, int tamano, FiltrosVista filtros) {
        LocalDateTime desde = FechaParser.ahora().minusHours(48);
        return paginarCacheado(compraAgilRepository.findByFechaPublicacionDesde(desde), pagina, tamano, filtros);
    }

    // Igual que listarUltimasOchoHorasCacheado, pero acotado a las compras
    // que estan ACTUALMENTE en 2do llamado (el primero ya cerro sin
    // suficientes ofertas y el segundo todavia esta abierto) -- boton
    // "En 2do llamado" en CompraRapida.jsx. Mismo criterio de lectura
    // directa del cache, sin pegarle en vivo a la API externa.
    public CompraAgilListadoResponse listarSegundoLlamadoCacheado(AuthenticatedPrincipal principal, String authorizationHeader, int pagina, int tamano, FiltrosVista filtros) {
        LocalDateTime desde = FechaParser.ahora().minusHours(48);
        LocalDateTime ahora = FechaParser.ahora();
        return paginarCacheado(compraAgilRepository.findEnSegundoLlamadoDesde(desde, ahora), pagina, tamano, filtros);
    }

    // Barra de busqueda por palabra clave (ver CompraAgilController) --
    // busca en el CACHE local, no en vivo contra Mercado Publico (esa es
    // la busqueda por codigo exacto de getDetalleByCodigo, o la Puerta 2
    // de buscar() para GLOBAL). Abierta a cualquier usuario autenticado,
    // mismo criterio que listarUltimasOchoHorasCacheado (la cartera
    // cacheada ya no se acota por perfil/asignaciones).
    public CompraAgilListadoResponse buscarPorTexto(String texto, int pagina, int tamano, FiltrosVista filtros) {
        return paginarCacheado(compraAgilRepository.buscarPorTexto(texto), pagina, tamano, filtros);
    }

    // Compartido por listarUltimasOchoHorasCacheado y
    // listarSegundoLlamadoCacheado -- ambos leen un universo distinto del
    // cache pero paginan igual (mismo record Paginacion que devuelve
    // Mercado Publico para la busqueda en vivo, asi el front trata ambas
    // respuestas igual).
    // Cierre "real" a efectos de filtro/orden -- mismo criterio que ya usa
    // el badge de cierre del front (ver fechas.js/DetalleItem.jsx): si hay
    // 2do llamado vigente, ese es el que manda, no la fecha del 1ro (que ya
    // paso y confundiria tanto el orden como el filtro por rango).
    private static LocalDateTime cierreEfectivo(CompraAgilEntity c) {
        return c.getFechaCierreSegundoLlamado() != null ? c.getFechaCierreSegundoLlamado() : c.getFechaCierre();
    }

    // Aplica filtros (region/monto/rango de cierre) y orden sobre lo que ya
    // trajo la consulta base (todas/mi-filtro/2do llamado/busqueda por
    // texto) -- ver CompraAgilDto.FiltrosVista. Todo en memoria: el
    // universo que llega acá ya viene acotado por su propia consulta (48h
    // de publicacion, o el perfil de la empresa), nunca es "toda la tabla".
    private List<CompraAgilEntity> aplicarFiltros(List<CompraAgilEntity> entidades, FiltrosVista filtros) {
        LocalDateTime ahora = FechaParser.ahora();
        // Las que ya cerraron no se muestran en las listas -- pedido
        // explicito 2026-09-30: solo se ven si se buscan a mano por codigo
        // (getDetalleByCodigo NO pasa por este metodo). Sin cierreEfectivo
        // (nunca se sincronizo el detalle completo) se deja pasar -- no hay
        // forma de saber si cerro o no, mejor mostrarla que esconderla.
        var stream = entidades.stream()
                .filter(c -> cierreEfectivo(c) == null || cierreEfectivo(c).isAfter(ahora));

        if (filtros != null) {
            if (filtros.region() != null) {
                stream = stream.filter(c -> filtros.region().equals(c.getRegion()));
            }
            if (filtros.montoMin() != null) {
                stream = stream.filter(c -> montoRelevante(c) != null && montoRelevante(c).compareTo(filtros.montoMin()) >= 0);
            }
            if (filtros.montoMax() != null) {
                stream = stream.filter(c -> montoRelevante(c) != null && montoRelevante(c).compareTo(filtros.montoMax()) <= 0);
            }
            if (filtros.cierreDesde() != null) {
                LocalDateTime desde = filtros.cierreDesde().atStartOfDay();
                stream = stream.filter(c -> cierreEfectivo(c) != null && !cierreEfectivo(c).isBefore(desde));
            }
            if (filtros.cierreHasta() != null) {
                LocalDateTime hasta = filtros.cierreHasta().plusDays(1).atStartOfDay();
                stream = stream.filter(c -> cierreEfectivo(c) != null && cierreEfectivo(c).isBefore(hasta));
            }
            if (filtros.palabraClave() != null && !filtros.palabraClave().isBlank()) {
                String texto = filtros.palabraClave().toLowerCase();
                stream = stream.filter(c ->
                        (c.getNombre() != null && c.getNombre().toLowerCase().contains(texto))
                        || (c.getDescripcion() != null && c.getDescripcion().toLowerCase().contains(texto))
                        || (c.getOrganismoComprador() != null && c.getOrganismoComprador().toLowerCase().contains(texto)));
            }
        }

        Comparator<CompraAgilEntity> comparador = switch (filtros != null && filtros.ordenarPor() != null ? filtros.ordenarPor() : "cierre") {
            case "publicacion" -> Comparator.comparing(CompraAgilEntity::getFechaPublicacion, Comparator.nullsLast(Comparator.naturalOrder()));
            case "monto" -> Comparator.comparing(CompraAgilService::montoRelevante, Comparator.nullsLast(Comparator.naturalOrder()));
            case "nombre" -> Comparator.comparing(CompraAgilEntity::getNombre, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            default -> Comparator.comparing(CompraAgilService::cierreEfectivo, Comparator.nullsLast(Comparator.naturalOrder()));
        };
        if (filtros != null && "desc".equalsIgnoreCase(filtros.direccion())) {
            comparador = comparador.reversed();
        }

        return stream.sorted(comparador).toList();
    }

    private CompraAgilListadoResponse paginarCacheado(List<CompraAgilEntity> entidades, int pagina, int tamano, FiltrosVista filtros) {
        List<Item> items = aplicarFiltros(entidades, filtros).stream()
                .map(compraAgilMapper::toItemDto)
                .toList();

        int tam = tamano > 0 ? tamano : TAMANO_PAGINA_DEFECTO;
        int totalPaginas = Math.max(1, (int) Math.ceil(items.size() / (double) tam));
        int pag = Math.min(Math.max(pagina, 1), totalPaginas);

        int desdeIdx = (pag - 1) * tam;
        int hastaIdx = Math.min(desdeIdx + tam, items.size());
        List<Item> pagina_ = desdeIdx < hastaIdx ? items.subList(desdeIdx, hastaIdx) : List.of();

        return new CompraAgilListadoResponse("true", null,
                new Listado(pagina_, new Paginacion(totalPaginas, pag, tam, items.size())), null);
    }

    // findById + merge + save dentro de UNA transaccion (ver comentario de
    // transactionTemplate). Un item que falla no tumba el resto del listado,
    // pero ahora queda en el log en vez de tragarse en silencio.
    //
    // Devuelve true si a esta compra le hace falta pedir el detalle: es
    // nueva, todavia no tiene el detalle completo, o su fecha_ultimo_cambio
    // cambio respecto a lo que habia en cache (se compara ANTES de pisarla
    // con la del listado).
    private boolean guardarItemEnCache(Item item) {
        if (item == null || item.codigo() == null || item.codigo().isBlank()) {
            return false;
        }
        try {
            Boolean necesitaDetalle = transactionTemplate.execute(estado -> {
                CompraAgilEntity existente = compraAgilRepository.findById(item.codigo()).orElse(null);
                boolean necesita = existente == null
                        || !Boolean.TRUE.equals(existente.getDetalleCompleto())
                        || cambioDesdeUltimoSync(existente, item);
                compraAgilRepository.save(compraAgilMapper.mergeFromItem(existente, item, LocalDateTime.now()));
                return necesita;
            });
            return !Boolean.FALSE.equals(necesitaDetalle);
        } catch (Exception e) {
            log.warn("No se pudo cachear el item {} del listado de compra agil: {}", item.codigo(), e.getMessage(), e);
            // Se intenta igual el detalle: si sale bien, crea/actualiza la fila.
            return true;
        }
    }

    private static boolean cambioDesdeUltimoSync(CompraAgilEntity existente, Item item) {
        LocalDateTime cambioListado = item.fechas() != null
                ? FechaParser.parse(item.fechas().fecha_ultimo_cambio())
                : null;
        return cambioListado != null && !cambioListado.equals(existente.getFechaUltimoCambio());
    }

    // A diferencia de guardarItemEnCache, aca el error SI se propaga: el
    // scheduler tiene que contarlo como fallo del detalle.
    private void guardarDetalleEnCache(Detalle detalle) {
        transactionTemplate.executeWithoutResult(estado -> {
            CompraAgilEntity existente = compraAgilRepository.findById(detalle.codigo()).orElse(null);
            compraAgilRepository.save(compraAgilMapper.toEntity(detalle, existente, LocalDateTime.now()));
        });
    }
}
