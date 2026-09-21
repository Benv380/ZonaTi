package cl.zona_ti.auth_service.Dto;

import jakarta.validation.constraints.Size;

// Edicion de datos de perfil. "password" es opcional -- si viene vacio/
// null, no se toca la que ya tiene. "rolNombre"/"empresaId" tambien son
// opcionales y SOLO los aplica UsuarioService.actualizar() cuando quien
// edita es GLOBAL -- si los manda un ADMIN_EMPRESA, se ignoran en
// silencio (mismo criterio que en CrearUsuarioRequest: la restriccion
// vive en el service, no confiando en que el front no los mande).
public class ActualizarUsuarioRequest {

    private String name;
    private String lastName;
    private String email;

    @Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres")
    private String password;

    private String rolNombre;
    private Long empresaId;

    // Boolean (no boolean) para distinguir "no venia en el request" (null,
    // no se toca) de "se mando explicito false" -- a diferencia de rol/
    // empresa, esto lo puede tocar CUALQUIER admin (GLOBAL o EMPRESA) que
    // ya tenga permiso sobre el usuario, no exclusivo GLOBAL (ver
    // UsuarioService.actualizar).
    private Boolean requiereSupervision;

    public ActualizarUsuarioRequest() {
    }

    public Boolean getRequiereSupervision() {
        return requiereSupervision;
    }

    public void setRequiereSupervision(Boolean requiereSupervision) {
        this.requiereSupervision = requiereSupervision;
    }

    public String getRolNombre() {
        return rolNombre;
    }

    public void setRolNombre(String rolNombre) {
        this.rolNombre = rolNombre;
    }

    public Long getEmpresaId() {
        return empresaId;
    }

    public void setEmpresaId(Long empresaId) {
        this.empresaId = empresaId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
