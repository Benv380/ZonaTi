package cl.zona_ti.auth_service.Dto;

import jakarta.validation.constraints.Size;

// Texto libre de la fase DESARROLLO (ver DetalleDesarrollo.jsx en el
// front) -- a diferencia de ActualizarCotizacionRequest (JSON opaco), este
// campo SI se valida aca: es texto real, no algo que arma y calcula el
// front, asi que tiene sentido aplicar el mismo limite que el campo real
// de Mercado Publico en el que se basa. Puede venir vacio/null para
// borrar el detalle.
public class ActualizarDetalleDesarrolloRequest {

    @Size(max = 255, message = "El detalle no puede superar los 255 caracteres")
    private String detalleDesarrollo;

    public ActualizarDetalleDesarrolloRequest() {
    }

    public String getDetalleDesarrollo() {
        return detalleDesarrollo;
    }

    public void setDetalleDesarrollo(String detalleDesarrollo) {
        this.detalleDesarrollo = detalleDesarrollo;
    }
}
