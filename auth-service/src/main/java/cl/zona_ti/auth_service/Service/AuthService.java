package cl.zona_ti.auth_service.Service;

import java.util.Optional;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import cl.zona_ti.auth_service.Model.User;
import cl.zona_ti.auth_service.Repository.UserRepository;
import cl.zona_ti.auth_service.Security.JwtUtil;
import cl.zona_ti.auth_service.Dto.LoginRequest;

@Service
public class AuthService {

    // Hash BCrypt "señuelo" (sin usuario dueño) contra el que comparamos
    // cuando el email no existe, para que un login con email inválido tarde
    // lo mismo que uno con password incorrecta. Sin esto, un atacante
    // podría enumerar usuarios validos midiendo el tiempo de respuesta
    // (findByEmail + BCrypt.matches es mucho más lento que solo el fallo
    // inmediato del orElseThrow).
    private static final String DUMMY_PASSWORD_HASH = BCrypt.hashpw(
            "dummy-password-para-igualar-tiempos", BCrypt.gensalt(12));

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokenService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtUtil jwtUtil,
            RefreshTokenService refreshTokenService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.refreshTokenService = refreshTokenService;
    }

    // Resultado interno con los 2 tokens -- AuthController decide que hacer
    // con cada uno (el access token va en el body JSON, el refresh token
    // SOLO en una cookie httpOnly, nunca en el body -- si viajara en el
    // JSON, cualquier JS del front podria leerlo, perdiendo la proteccion
    // contra XSS que es la razon de ser de la cookie httpOnly).
    public record LoginResult(String accessToken, String refreshToken, String type) {
    }

    public LoginResult authenticate(LoginRequest request) {
        Optional<User> maybeUser = userRepository.findByEmail(request.getEmail());

        // Siempre se ejecuta un BCrypt.matches, exista o no el usuario, y el
        // mensaje de error es idéntico en ambos casos: no se revela si el
        // email es válido ni por qué falló el login.
        String hashToCheck = maybeUser.map(User::getPassword).orElse(DUMMY_PASSWORD_HASH);
        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), hashToCheck);

        if (maybeUser.isEmpty() || !passwordMatches) {
            throw new BadCredentialsException("Credenciales inválidas");
        }

        User usuario = maybeUser.get();
        String accessToken = jwtUtil.generateToken(usuario);
        String refreshToken = refreshTokenService.crear(usuario);

        return new LoginResult(accessToken, refreshToken, "Bearer");
    }

    // Cambia un refresh token vigente por un access token nuevo -- y de
    // paso ROTA el refresh token (uno nuevo, el viejo queda revocado, ver
    // RefreshTokenService.validarYRotar). Tira BadCredentialsException si
    // el refresh token no existe, vencio o ya fue usado -- AuthController
    // lo traduce a 401 y limpia la cookie.
    public LoginResult refrescar(String refreshTokenValue) {
        User usuario = refreshTokenService.validarYRotar(refreshTokenValue);
        String accessToken = jwtUtil.generateToken(usuario);
        String nuevoRefreshToken = refreshTokenService.crear(usuario);
        return new LoginResult(accessToken, nuevoRefreshToken, "Bearer");
    }

    public void cerrarSesion(String refreshTokenValue) {
        refreshTokenService.revocar(refreshTokenValue);
    }
}
