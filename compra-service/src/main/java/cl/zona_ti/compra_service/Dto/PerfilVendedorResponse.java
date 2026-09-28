package cl.zona_ti.compra_service.Dto;

import java.math.BigDecimal;
import java.util.List;

// Panel "Perfil del ganador" (ver CompraAgilService.perfilVendedor): se
// arma agregando, del lado nuestro, todas las cotizaciones de un mismo
// proveedor (por RUT) a lo largo de las compras agiles ya cacheadas CON
// DETALLE COMPLETO -- a diferencia del perfil de comprador, esto solo
// existe para la minoria de compras cuyo detalle se sincronizo entero (ver
// CompraAgilProveedorCotizandoRepository), asi que el universo es mas
// chico.
public record PerfilVendedorResponse(
        String rutProveedor,
        String razonSocial,
        long totalCotizaciones,
        long vecesGanador,
        double tasaAdjudicacion,
        BigDecimal montoPromedioGanado,
        BigDecimal montoTotalGanado,
        List<ConteoTexto> organismosFrecuentes
) {
    public record ConteoTexto(String texto, long cantidad) {
    }
}
