package cl.zona_ti.compra_service.Dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Respuesta de https://comprador-api-pro.mercadopublico.cl/comprador/reclamos/calculos_reclamos?rut=...
// (API interna/no oficial de la ficha publica de comprador -- ver
// comentario en application.yml). Se usa SOLO el agregado
// (calculos_reclamos) -- nunca detalle_reclamos, que trae el nombre real
// de la persona que reclamo (dato personal, no lo vamos a cachear ni
// mostrar, decision explicita 2026-09-25).
public class ReclamosDto {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CalculosReclamosResponse(
            String success,
            String trace,
            Payload payload,
            List<Object> errores) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payload(
            Periodo periodo,
            List<ReclamoPorTipo> data,
            Integer totalReclamos) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Periodo(
            Integer mesInicial,
            Integer añoInicial,
            Integer mesFinal,
            Integer añoFinal) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReclamoPorTipo(
            Integer tipoReclamoId,
            String nombre,
            Integer cantidadReclamos,
            Double porcentajeReclamo) {
    }
}
