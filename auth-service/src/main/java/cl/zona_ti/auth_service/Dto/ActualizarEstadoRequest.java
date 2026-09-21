package cl.zona_ti.auth_service.Dto;

import jakarta.validation.constraints.NotNull;

import cl.zona_ti.auth_service.Model.EstadoAsignacion;

// "motivoDescarte" es obligatorio SOLO si estado = DESCARTADO -- se
// valida a mano en AsignacionService.actualizarEstado (no con
// @NotBlank aca, porque para el resto de los estados no aplica).
public class ActualizarEstadoRequest {

    @NotNull(message = "Debe indicar el nuevo estado")
    private EstadoAsignacion estado;

    private String motivoDescarte;

    public ActualizarEstadoRequest() {
    }

    public EstadoAsignacion getEstado() {
        return estado;
    }

    public void setEstado(EstadoAsignacion estado) {
        this.estado = estado;
    }

    public String getMotivoDescarte() {
        return motivoDescarte;
    }

    public void setMotivoDescarte(String motivoDescarte) {
        this.motivoDescarte = motivoDescarte;
    }
}
