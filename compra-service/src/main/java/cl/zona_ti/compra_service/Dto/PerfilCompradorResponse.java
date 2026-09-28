package cl.zona_ti.compra_service.Dto;

import java.math.BigDecimal;
import java.util.List;

// Panel "perfil del comprador" (ver CompraAgilService.perfilComprador): se
// arma agregando, del lado nuestro, TODAS las compras agiles ya cacheadas
// de un mismo organismo (identificado por RUT, no por nombre -- el nombre
// puede venir con variaciones de mayusculas/espacios entre compras). No es
// data que traiga Mercado Publico ya armada, se calcula acá.
public record PerfilCompradorResponse(
        String rutInstitucion,
        String organismoComprador,
        long totalCompras,
        BigDecimal montoTotal,
        BigDecimal montoPromedio,
        // "demandas" (total_demandas) y "multa_sancion" son campos que ya
        // trae el resumen de Mercado Publico por cada compra (Guia de Uso API
        // Compra Agil v2) pero hasta ahora no se mostraban en ningun lado --
        // es lo mas cercano a "reclamos" que tenemos confirmado y ya
        // cacheado (ver memoria de la sesion 2026-09-25).
        long comprasConDemandas,
        long totalDemandas,
        long comprasConMultaSancion,
        BigDecimal multaSancionTotal,
        List<ConteoMensual> comprasPorMes,
        List<ConteoTexto> convocatoriasFrecuentes,
        // Reclamos reales (ChileCompra, ultimos 12 meses) -- ver
        // ReclamosClient/ReclamosService. null cuando no esta disponible
        // (token no configurado/vencido, o la llamada fallo) -- el front
        // omite la seccion entera en ese caso, nunca muestra "0 reclamos"
        // como si fuera un dato confirmado cuando en realidad no se pudo
        // consultar.
        ReclamosResumen reclamos
) {
    public record ConteoMensual(String mes, long cantidad) {
    }

    public record ConteoTexto(String texto, long cantidad) {
    }

    public record ReclamosResumen(int total, List<ReclamoPorTipo> porTipo) {
    }

    public record ReclamoPorTipo(String tipo, int cantidad, double porcentaje) {
    }
}
