package cl.zona_ti.auth_service.Dto;

import jakarta.validation.constraints.Size;

// Lo propio de la cotizacion (logo + condiciones comerciales), mas
// direccion/telefono -- estos ultimos SI son los mismos campos oficiales
// de la empresa (Empresa.direccion/telefono), simplemente se habilita su
// edicion desde aca ademas de desde Administracion: un ADMIN_EMPRESA no
// tiene acceso a ese panel (exclusivo GLOBAL), asi que sin esto no podria
// corregir su propia direccion/telefono aunque sean datos suyos.
// Nombre/RUT quedan afuera a proposito -- son identidad legal, se
// mantienen exclusivos de Administracion. "logoBase64" opcional: si viene
// null, el logo actual (si hay uno) no se toca -- mismo patron que
// CrearEmpresaRequest.logoBase64. Las dimensiones/peso del logo se
// validan en MarcaCotizacionService (limite bajo a proposito, es para un
// membrete chico, no para una imagen cualquiera).
public class ActualizarMarcaCotizacionRequest {

    private String direccion;

    private String telefono;

    private String logoBase64;

    private String logoTipoContenido;

    @Size(max = 2000, message = "Las condiciones comerciales no pueden superar los 2000 caracteres")
    private String condicionesComerciales;

    // Se valida contra una paleta fija chica en MarcaCotizacionService
    // (no cualquier hex) -- ver ColorCotizacion alli.
    private String colorPrincipal;

    // Datos bancarios para el pie de la cotizacion en PDF.
    private String banco;
    private String tipoCuenta;
    private String numeroCuenta;

    public ActualizarMarcaCotizacionRequest() {
    }

    public String getBanco() {
        return banco;
    }

    public void setBanco(String banco) {
        this.banco = banco;
    }

    public String getTipoCuenta() {
        return tipoCuenta;
    }

    public void setTipoCuenta(String tipoCuenta) {
        this.tipoCuenta = tipoCuenta;
    }

    public String getNumeroCuenta() {
        return numeroCuenta;
    }

    public void setNumeroCuenta(String numeroCuenta) {
        this.numeroCuenta = numeroCuenta;
    }

    public String getDireccion() {
        return direccion;
    }

    public void setDireccion(String direccion) {
        this.direccion = direccion;
    }

    public String getTelefono() {
        return telefono;
    }

    public void setTelefono(String telefono) {
        this.telefono = telefono;
    }

    public String getLogoBase64() {
        return logoBase64;
    }

    public void setLogoBase64(String logoBase64) {
        this.logoBase64 = logoBase64;
    }

    public String getLogoTipoContenido() {
        return logoTipoContenido;
    }

    public void setLogoTipoContenido(String logoTipoContenido) {
        this.logoTipoContenido = logoTipoContenido;
    }

    public String getCondicionesComerciales() {
        return condicionesComerciales;
    }

    public void setCondicionesComerciales(String condicionesComerciales) {
        this.condicionesComerciales = condicionesComerciales;
    }

    public String getColorPrincipal() {
        return colorPrincipal;
    }

    public void setColorPrincipal(String colorPrincipal) {
        this.colorPrincipal = colorPrincipal;
    }
}
