package cl.zona_ti.auth_service.Dto;

import jakarta.validation.constraints.NotBlank;

// Seteo manual del perfil por ahora (rubro/palabras clave/region a mano) --
// mas adelante el endpoint de onboarding con LLM va a llamar al MISMO
// PerfilBusquedaService.actualizar() con los valores que extraiga del
// texto libre, en vez de que el usuario los escriba el mismo aca.
public class ActualizarPerfilRequest {

    @NotBlank(message = "El rubro es obligatorio")
    private String rubro;

    @NotBlank(message = "Las palabras clave son obligatorias")
    private String palabrasClave;

    @NotBlank(message = "El código de región es obligatorio")
    private String regionCodigo;

    private String regionNombre;

    public ActualizarPerfilRequest() {
    }

    public String getRubro() {
        return rubro;
    }

    public void setRubro(String rubro) {
        this.rubro = rubro;
    }

    public String getPalabrasClave() {
        return palabrasClave;
    }

    public void setPalabrasClave(String palabrasClave) {
        this.palabrasClave = palabrasClave;
    }

    public String getRegionCodigo() {
        return regionCodigo;
    }

    public void setRegionCodigo(String regionCodigo) {
        this.regionCodigo = regionCodigo;
    }

    public String getRegionNombre() {
        return regionNombre;
    }

    public void setRegionNombre(String regionNombre) {
        this.regionNombre = regionNombre;
    }
}
