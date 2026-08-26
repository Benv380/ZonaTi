package cl.zona_ti.compra_service.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

import cl.zona_ti.compra_service.Client.CompraAgilClient;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.CompraAgilDetalleResponse;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.CompraAgilListadoResponse;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.Detalle;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.Item;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.Listado;
import cl.zona_ti.compra_service.Dto.CompraAgilDto.Paginacion;
import cl.zona_ti.compra_service.Mapper.CompraAgilMapper;
import cl.zona_ti.compra_service.Model.CompraAgilEntity;
import cl.zona_ti.compra_service.Repository.CompraAgilRepository;

@Service
public class CompraAgilService {

    private final CompraAgilClient compraAgilClient;
    private final CompraAgilRepository compraAgilRepository;
    private final CompraAgilMapper compraAgilMapper;

    // Mismo TTL que Licitacion (ver LicitacionService): tiempo que se considera
    // fresco un detalle cacheado antes de volver a pedirlo a la API real.
    @Value("${compra-service.cache.ttl-minutos:10}")
    private long ttlMinutos;

    public CompraAgilService(CompraAgilClient compraAgilClient, CompraAgilRepository compraAgilRepository,
            CompraAgilMapper compraAgilMapper) {
        this.compraAgilClient = compraAgilClient;
        this.compraAgilRepository = compraAgilRepository;
        this.compraAgilMapper = compraAgilMapper;
    }

    // El listado admite demasiados filtros libres (fecha, region, texto, etc.)
    // como para decidir de forma confiable si lo que hay en cache alcanza para
    // responder una combinacion en particular, asi que siempre se pide a la API
    // real. Lo que si se hace es historizar cada item que llega: sirve para que
    // getDetalleByCodigo() y listarUltimasOchoHoras() tengan de donde cachear.
    public CompraAgilListadoResponse listar(Map<String, String> filtros) {
        CompraAgilListadoResponse respuesta = compraAgilClient.listar(filtros);
        if (respuesta != null && respuesta.payload() != null && respuesta.payload().items() != null) {
            for (Item item : respuesta.payload().items()) {
                guardarItemEnCache(item);
            }
        }
        return respuesta;
    }

    public CompraAgilDetalleResponse getDetalleByCodigo(String codigo) {
        Optional<CompraAgilEntity> cacheada = compraAgilRepository.findById(codigo);
        if (cacheada.isPresent() && esDetalleFresco(cacheada.get())) {
            return new CompraAgilDetalleResponse("true", null, compraAgilMapper.toDetalleDto(cacheada.get()), null);
        }

        CompraAgilDetalleResponse respuesta = compraAgilClient.getDetalleByCodigo(codigo);
        if (respuesta != null && respuesta.payload() != null) {
            guardarDetalleEnCache(respuesta.payload());
        }
        return respuesta;
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
    public CompraAgilListadoResponse listarUltimasOchoHorasCacheado() {
        LocalDateTime desde = LocalDateTime.now(ZoneOffset.UTC).minusHours(48);
        List<Item> items = compraAgilRepository.findByFechaPublicacionDesde(desde).stream()
                .map(compraAgilMapper::toItemDto)
                .toList();
        return new CompraAgilListadoResponse("true", null,
                new Listado(items, new Paginacion(1, 1, items.size(), items.size())), null);
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
