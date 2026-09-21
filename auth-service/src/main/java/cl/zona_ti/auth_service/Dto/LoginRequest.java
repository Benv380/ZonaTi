package cl.zona_ti.auth_service.Dto;

public class LoginRequest {

    // El login es por email, no por username (username sigue existiendo,
    // pero ahora es solo el identificador que se muestra en el resto de la
    // app -- ver AuthService.authenticate).
    private String email;
    private String password;

    public LoginRequest() {
    }

    public LoginRequest(String email, String password) {
        this.email = email;
        this.password = password;
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
