package cl.zona_ti.auth_service.Dto;

import jakarta.validation.constraints.NotBlank;

// Las credenciales llegan en texto plano SOLO en este request -- se cifran
// (ver CifradoAesService) antes de tocar la base de datos, nunca se
// guardan tal cual llegan. La empresa las carga ella misma, con
// conocimiento de para que se usan (ver ChileCompraCredencialesService).
public class ActualizarChileCompraCredencialesRequest {

    @NotBlank(message = "El usuario de Chile Compra es obligatorio")
    private String chileCompraUsuario;

    @NotBlank(message = "La contraseña de Chile Compra es obligatoria")
    private String chileCompraPassword;

    public ActualizarChileCompraCredencialesRequest() {
    }

    public String getChileCompraUsuario() {
        return chileCompraUsuario;
    }

    public void setChileCompraUsuario(String chileCompraUsuario) {
        this.chileCompraUsuario = chileCompraUsuario;
    }

    public String getChileCompraPassword() {
        return chileCompraPassword;
    }

    public void setChileCompraPassword(String chileCompraPassword) {
        this.chileCompraPassword = chileCompraPassword;
    }
}
