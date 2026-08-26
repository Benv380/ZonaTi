package cl.zona_ti.auth_service.Service;

import java.util.Optional;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import cl.zona_ti.auth_service.Model.User;
import cl.zona_ti.auth_service.Repository.UserRepository;
import cl.zona_ti.auth_service.Security.JwtUtil;
import cl.zona_ti.auth_service.Dto.AuthResponse;
import cl.zona_ti.auth_service.Dto.LoginRequest;

@Service
public class AuthService {

    // Hash BCrypt "señuelo" (sin usuario dueño) contra el que comparamos
    // cuando el username no existe, para que un login con usuario inválido
    // tarde lo mismo que uno con password incorrecta. Sin esto, un atacante
    // podría enumerar usuarios validos midiendo el tiempo de respuesta
    // (findByUsername + BCrypt.matches es mucho más lento que solo el fallo
    // inmediato del orElseThrow).
    private static final String DUMMY_PASSWORD_HASH = BCrypt.hashpw(
            "dummy-password-para-igualar-tiempos", BCrypt.gensalt(12));

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtUtil jwtUtil
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    public AuthResponse authenticate(LoginRequest request) {
        Optional<User> maybeUser = userRepository.findByUsername(request.getUsername());

        // Siempre se ejecuta un BCrypt.matches, exista o no el usuario, y el
        // mensaje de error es idéntico en ambos casos: no se revela si el
        // username es válido ni por qué falló el login.
        String hashToCheck = maybeUser.map(User::getPassword).orElse(DUMMY_PASSWORD_HASH);
        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), hashToCheck);

        if (maybeUser.isEmpty() || !passwordMatches) {
            throw new BadCredentialsException("Credenciales inválidas");
        }

        String token = jwtUtil.generateToken(maybeUser.get());

        return new AuthResponse(token, "Bearer");
    }
}