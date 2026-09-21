package cl.zona_ti.auth_service.Dto;

import jakarta.validation.constraints.NotBlank;

public class CrearEmpresaRequest {

    @NotBlank(message = "El nombre de la empresa es obligatorio")
    private String nombre;

    private String rut;

    private String rubro;

    private String representanteLegal;

    private String direccion;

    private String comuna;

    private String region;

    private String telefono;

    private String email;

    private String nombreFantasia;

    private String giro;

    private String rutRepresentanteLegal;

    private String tamanoEmpresa;

    private boolean chileproveedoresRegistrado;

    private String chileproveedoresCodigo;

    private String sitioWeb;

    private String estado;

    // Base64 plano (sin el prefijo "data:image/...;base64,") -- el front
    // lo arma con FileReader.readAsDataURL y le saca ese prefijo antes de
    // mandarlo. Opcional: si viene null, el logo actual (si hay uno) no
    // se toca.
    private String logoBase64;

    private String logoTipoContenido;

    public CrearEmpresaRequest() {
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getRut() {
        return rut;
    }

    public void setRut(String rut) {
        this.rut = rut;
    }

    public String getRubro() {
        return rubro;
    }

    public void setRubro(String rubro) {
        this.rubro = rubro;
    }

    public String getRepresentanteLegal() {
        return representanteLegal;
    }

    public void setRepresentanteLegal(String representanteLegal) {
        this.representanteLegal = representanteLegal;
    }

    public String getDireccion() {
        return direccion;
    }

    public void setDireccion(String direccion) {
        this.direccion = direccion;
    }

    public String getComuna() {
        return comuna;
    }

    public void setComuna(String comuna) {
        this.comuna = comuna;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getTelefono() {
        return telefono;
    }

    public void setTelefono(String telefono) {
        this.telefono = telefono;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getNombreFantasia() {
        return nombreFantasia;
    }

    public void setNombreFantasia(String nombreFantasia) {
        this.nombreFantasia = nombreFantasia;
    }

    public String getGiro() {
        return giro;
    }

    public void setGiro(String giro) {
        this.giro = giro;
    }

    public String getRutRepresentanteLegal() {
        return rutRepresentanteLegal;
    }

    public void setRutRepresentanteLegal(String rutRepresentanteLegal) {
        this.rutRepresentanteLegal = rutRepresentanteLegal;
    }

    public String getTamanoEmpresa() {
        return tamanoEmpresa;
    }

    public void setTamanoEmpresa(String tamanoEmpresa) {
        this.tamanoEmpresa = tamanoEmpresa;
    }

    public boolean isChileproveedoresRegistrado() {
        return chileproveedoresRegistrado;
    }

    public void setChileproveedoresRegistrado(boolean chileproveedoresRegistrado) {
        this.chileproveedoresRegistrado = chileproveedoresRegistrado;
    }

    public String getChileproveedoresCodigo() {
        return chileproveedoresCodigo;
    }

    public void setChileproveedoresCodigo(String chileproveedoresCodigo) {
        this.chileproveedoresCodigo = chileproveedoresCodigo;
    }

    public String getSitioWeb() {
        return sitioWeb;
    }

    public void setSitioWeb(String sitioWeb) {
        this.sitioWeb = sitioWeb;
    }

    public String getEstado() {
        return estado;
    }

    public void setEstado(String estado) {
        this.estado = estado;
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
}
