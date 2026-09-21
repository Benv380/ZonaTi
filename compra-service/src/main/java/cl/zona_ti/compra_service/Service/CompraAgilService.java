package cl.zona_ti.compra_service.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

import cl.zona_ti.compra_service.Client.CompraAgilClient;
import cl.zona_ti.compra_service.Client.PerfilClient;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.CompraAgilDetalleResponse;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.CompraAgilListadoResponse;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.Detalle;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.Item;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.Listado;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.Paginacion;
import cl.zona_ti.compra_service.Dto.PerfilBusquedaDto;
import cl.zona_ti.compra_service.Mapper.CompraAgilMapper;
import cl.zona_ti.compra_service.Model.Alcance;
import cl.zona_ti.compra_service.Model.CompraAgilEntity;
import cl.zona_ti.compra_service.Repository.CompraAgilRepository;
import cl.zona_ti.compra_service.Security.AuthenticatedPrincipal;

@Service
public class CompraAgilService {

    private final CompraAgilClient compraAgilClient;
    private final CompraAgilRepository compraAgilRepository;
    private final CompraAgilMapper compraAgilMapper;
    private final PerfilClient perfilClient;

    // Tamaño de pagina por defecto para /listar -- ver
    // listarUltimasOchoHorasCacheado().
    private static final int TAMANO_PAGINA_DEFECTO = 15;

    // Mismo TTL que Licitacion (ver LicitacionService): tiempo que se considera
    // fresco un detalle cacheado antes de volver a pedirlo a la API real.
    @Value("${compra-service.cache.ttl-minutos:10}")
    private long ttlMinutos;

    public CompraAgilService(CompraAgilClient compraAgilClient, CompraAgilRepository compraAgilRepository,
            CompraAgilMapper compraAgilMapper, PerfilClient perfilClient) {
        this.compraAgilClient = compraAgilClient;
        this.compraAgilRepository = compraAgilRepository;
        this.compraAgilMapper = compraAgilMapper;
        this.perfilClient = perfilClient;
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

    private Map<String, String> filtrosDesdePerfil(String authorizationHeader) {
        try {
            PerfilBusquedaDto perfil = perfilClient.miPerfil(authorizationHeader);
            if (perfil == null || !perfil.perfilCompletado()) {
                return Map.of();
            }
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

    // Uso interno (CompraAgilSyncScheduler, corre en background sin ningun
    // usuario/request de por medio) -- sin principal, no hay nada que
    // filtrar.
    public CompraAgilDetalleResponse getDetalleByCodigo(String codigo) {
        return getDetalleByCodigo(codigo, null, null);
    }

    // Busqueda por codigo puntual -- ya no se acota por asignaciones (ver
    // listarUltimasOchoHorasCacheado): el catalogo completo es visible
    // para cualquier usuario autenticado.
    public CompraAgilDetalleResponse getDetalleByCodigo(String codigo, AuthenticatedPrincipal principal, String authorizationHeader) {
        Optional<CompraAgilEntity> cacheada = compraAgilRepository.findById(codigo);
        if (cacheada.isPresent() && esDetalleFresco(cacheada.get())) {
            return new CompraAgilDetalleResponse("true", null, compraAgilMapper.toDetalleDto(cacheada.get()), null);
        }

        CompraAgilDetalleResponse respuesta = compraAgilClient.getDetalleByCodigo(codigo);
        if (respuesta != null && respuesta.payload() != null) {
            guardarDetalleEnCache(respuesta.payload());
            return respuesta;
        }

        // Mismo criterio que LicitacionService.getLicitacionByCodigo: si
        // el codigo tal cual no encontro nada, se busca en el cache local
        // (ultimas 48h) un codigo cuya version normalizada (sin simbolos,
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

        return respuesta;
    }

    private static String normalizarParaComparar(String valor) {
        return valor == null ? "" : valor.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
    }

    private String buscarCodigoNormalizado(String entrada) {
        String entradaNormalizada = normalizarParaComparar(entrada);
        if (entradaNormalizada.isBlank()) {
            return null;
        }
        LocalDateTime desde = LocalDateTime.now(ZoneOffset.UTC).minusHours(48);
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
    public CompraAgilListadoResponse sincronizarUltimasOchoHoras() {
        OffsetDateTime hasta = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime desde = hasta.minusHours(48);

        Map<String, String> filtros = Map.of(
                "publicado_desde", desde.format(DateTimeFormatter.ISO_INSTANT),
                "publicado_hasta", hasta.format(DateTimeFormatter.ISO_INSTANT));

        // Reusa listar(): ademas de pedir el rango, deja cacheado cada item.
        return listar(filtros);
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
    public CompraAgilListadoResponse listarUltimasOchoHorasCacheado(AuthenticatedPrincipal principal, String authorizationHeader, int pagina, int tamano) {
        LocalDateTime desde = LocalDateTime.now(ZoneOffset.UTC).minusHours(48);
        return paginarCacheado(compraAgilRepository.findByFechaPublicacionDesde(desde), pagina, tamano);
    }

    // Igual que listarUltimasOchoHorasCacheado, pero acotado a las compras
    // que estan ACTUALMENTE en 2do llamado (el primero ya cerro sin
    // suficientes ofertas y el segundo todavia esta abierto) -- boton
    // "En 2do llamado" en CompraRapida.jsx. Mismo criterio de lectura
    // directa del cache, sin pegarle en vivo a la API externa.
    public CompraAgilListadoResponse listarSegundoLlamadoCacheado(AuthenticatedPrincipal principal, String authorizationHeader, int pagina, int tamano) {
        LocalDateTime desde = LocalDateTime.now(ZoneOffset.UTC).minusHours(48);
        LocalDateTime ahora = LocalDateTime.now(ZoneOffset.UTC);
        return paginarCacheado(compraAgilRepository.findEnSegundoLlamadoDesde(desde, ahora), pagina, tamano);
    }

    // Barra de busqueda por palabra clave (ver CompraAgilController) --
    // busca en el CACHE local, no en vivo contra Mercado Publico (esa es
    // la busqueda por codigo exacto de getDetalleByCodigo, o la Puerta 2
    // de buscar() para GLOBAL). Abierta a cualquier usuario autenticado,
    // mismo criterio que listarUltimasOchoHorasCacheado (la cartera
    // cacheada ya no se acota por perfil/asignaciones).
    public CompraAgilListadoResponse buscarPorTexto(String texto, int pagina, int tamano) {
        return paginarCacheado(compraAgilRepository.buscarPorTexto(texto), pagina, tamano);
    }

    // Compartido por listarUltimasOchoHorasCacheado y
    // listarSegundoLlamadoCacheado -- ambos leen un universo distinto del
    // cache pero paginan igual (mismo record Paginacion que devuelve
    // Mercado Publico para la busqueda en vivo, asi el front trata ambas
    // respuestas igual).
    private CompraAgilListadoResponse paginarCacheado(List<CompraAgilEntity> entidades, int pagina, int tamano) {
        List<Item> items = entidades.stream()
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

    private void guardarItemEnCache(Item item) {
        try {
            CompraAgilEntity existente = compraAgilRepository.findById(item.codigo()).orElse(null);
            compraAgilRepository.save(compraAgilMapper.mergeFromItem(existente, item, LocalDateTime.now()));
        } catch (Exception ignored) {
            // Un fallo al escribir en la cache no debe tumbar la respuesta al usuario.
        }
    }

    private void guardarDetalleEnCache(Detalle detalle) {
        try {
            CompraAgilEntity existente = compraAgilRepository.findById(detalle.codigo()).orElse(null);
            compraAgilRepository.save(compraAgilMapper.toEntity(detalle, existente, LocalDateTime.now()));
        } catch (Exception ignored) {
        }
    }

    private boolean esDetalleFresco(CompraAgilEntity entity) {
        LocalDateTime fechaSync = entity.getFechaSync();
        boolean tieneDetalleCompleto = Boolean.TRUE.equals(entity.getDetalleCompleto());
        return tieneDetalleCompleto && fechaSync != null && fechaSync.isAfter(LocalDateTime.now().minusMinutes(ttlMinutos));
    }
}
