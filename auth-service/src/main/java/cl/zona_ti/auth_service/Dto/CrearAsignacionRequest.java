package cl.zona_ti.auth_service.Dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import cl.zona_ti.auth_service.Model.TipoAsignacion;

public class CrearAsignacionRequest {

    @NotBlank(message = "El código externo es obligatorio")
    private String codigoExterno;

    @NotNull(message = "Debe indicar el tipo (licitación o compra ágil)")
    private TipoAsignacion tipo;

    // Solo lo usa POST /auth/me/asignaciones (recomendar) -- a quien se le
    // recomienda. Si viene null, se recomienda a si mismo (compatibilidad
    // con el flujo simple original). AsignacionService.recomendar valida
    // que sea de la MISMA empresa que quien recomienda. No lo usa
    // POST /auth/usuarios/{userId}/asignaciones (asignar) -- ahi el
    // destinatario ya va en la URL.
    private Long destinatarioUserId;

    public CrearAsignacionRequest() {
    }

    public String getCodigoExterno() {
        return codigoExterno;
    }

    public void setCodigoExterno(String codigoExterno) {
        this.codigoExterno = codigoExterno;
    }

    public TipoAsignacion getTipo() {
        return tipo;
    }

    public void setTipo(TipoAsignacion tipo) {
        this.tipo = tipo;
    }

    public Long getDestinatarioUserId() {
        return destinatarioUserId;
    }

    public void setDestinatarioUserId(Long destinatarioUserId) {
        this.destinatarioUserId = destinatarioUserId;
    }
}
