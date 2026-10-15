package cl.zona_ti.auth_service.Dto;

import jakarta.validation.constraints.NotBlank;

// Crear/editar UN filtro de busqueda (rubro/palabras clave/region a mano
// por ahora). Se usa tanto para crear (POST) como para editar (PUT) --
// misma forma en los dos casos.
public class ActualizarPerfilRequest {

    @NotBlank(message = "El nombre del filtro es obligatorio")
    private String nombre;

    @NotBlank(message = "El rubro es obligatorio")
    private String rubro;

    // Opcionales si el filtro tiene seleccion del catalogo (ver
    // PerfilBusquedaService.aplicarDatos: al menos una de las dos).
    private String palabrasClave;

    // Seleccion del catalogo de productos de MP (JSON, ver schema.sql).
    private String catalogoJson;

    // Sin @NotBlank (pedido explicito 2026-10-02, paridad con Api-Prueba):
    // vacio significa "todo el pais, sin restriccion de region" -- ver
    // CompraAgilService.parseRegion y el select "Todo el país (sin
    // restricción)" en MiEmpresa.jsx.
    private String regionCodigo;

    private String regionNombre;

    public ActualizarPerfilRequest() {
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
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

    public String getCatalogoJson() {
        return catalogoJson;
    }

    public void setCatalogoJson(String catalogoJson) {
        this.catalogoJson = catalogoJson;
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
