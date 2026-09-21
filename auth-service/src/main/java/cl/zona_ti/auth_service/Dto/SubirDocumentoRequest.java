package cl.zona_ti.auth_service.Dto;

import jakarta.validation.constraints.NotBlank;

// Mismo patron base64-en-JSON que ya usa el logo de la empresa (ver
// ActualizarMarcaCotizacionRequest) -- consistente con el resto del
// proyecto, en vez de introducir multipart/form-data para este unico caso.
public class SubirDocumentoRequest {

    @NotBlank
    private String nombreArchivo;

    private String tipoContenido;

    @NotBlank
    private String contenidoBase64;

    public SubirDocumentoRequest() {
    }

    public String getNombreArchivo() {
        return nombreArchivo;
    }

    public void setNombreArchivo(String nombreArchivo) {
        this.nombreArchivo = nombreArchivo;
    }

    public String getTipoContenido() {
        return tipoContenido;
    }

    public void setTipoContenido(String tipoContenido) {
        this.tipoContenido = tipoContenido;
    }

    public String getContenidoBase64() {
        return contenidoBase64;
    }

    public void setContenidoBase64(String contenidoBase64) {
        this.contenidoBase64 = contenidoBase64;
    }
}
