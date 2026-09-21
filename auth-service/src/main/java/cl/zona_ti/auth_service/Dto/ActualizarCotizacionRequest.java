package cl.zona_ti.auth_service.Dto;

// El contenido es JSON opaco para auth-service -- lo arma y calcula el
// front (CotizacionCompraAgil.jsx: lineas de producto, valor unitario,
// despacho, IVA, totales). Aca solo se guarda/devuelve tal cual, sin
// validar su estructura -- nunca vacio a proposito porque "" tambien
// sirve para borrar el borrador.
public class ActualizarCotizacionRequest {

    private String cotizacionJson;

    public ActualizarCotizacionRequest() {
    }

    public String getCotizacionJson() {
        return cotizacionJson;
    }

    public void setCotizacionJson(String cotizacionJson) {
        this.cotizacionJson = cotizacionJson;
    }
}
