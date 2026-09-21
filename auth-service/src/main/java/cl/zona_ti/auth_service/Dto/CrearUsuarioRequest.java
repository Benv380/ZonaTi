package cl.zona_ti.auth_service.Dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CrearUsuarioRequest {

    private String name;
    private String lastName;

    // Obligatorio: el login ahora es por email, no por username (ver
    // AuthService.authenticate) -- sin esto el usuario nuevo quedaria sin
    // forma de entrar.
    @NotBlank(message = "El correo es obligatorio")
    @Email(message = "El correo no tiene un formato válido")
    private String email;

    @NotBlank(message = "El nombre de usuario es obligatorio")
    private String username;

    @NotBlank(message = "La contraseña es obligatoria")
    @Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres")
    private String password;

    // Solo los tiene en cuenta un creador GLOBAL -- si el creador es
    // ADMIN_EMPRESA (alcance EMPRESA), UsuarioService los IGNORA por
    // completo y fuerza rol USER + su propia empresa, para que un
    // ADMIN_EMPRESA no pueda auto-escalarse privilegios ni crear usuarios
    // fuera de su empresa (ver UsuarioService.crear).
    private String rolNombre;
    private Long empresaId;

    public CrearUsuarioRequest() {
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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
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
}
