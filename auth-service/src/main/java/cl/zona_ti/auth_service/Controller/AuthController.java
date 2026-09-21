package cl.zona_ti.auth_service.Controller;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cl.zona_ti.auth_service.Dto.AuthResponse;
import cl.zona_ti.auth_service.Dto.LoginRequest;
import cl.zona_ti.auth_service.Service.AuthService;
import cl.zona_ti.auth_service.Service.AuthService.LoginResult;

@RestController
@RequestMapping("/auth")

// nota: esto no lleva @CrossOrigin, tanto en desarrollo
// (proxy de Vite) como en producción (nginx, ver nginx.conf) el front le
// habla a este endpoint como si fuera el mismo origen, así que no hace falta

public class AuthController {

    private static final String COOKIE_REFRESH = "refreshToken";
    private static final Duration DURACION_REFRESH = Duration.ofDays(7);

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest) {
        try {
            LoginResult resultado = authService.authenticate(loginRequest);
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, cookieRefresh(resultado.refreshToken()).toString())
                    .body(new AuthResponse(resultado.accessToken(), resultado.type()));
        } catch (AuthenticationException e) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body("Credenciales inválidas");
        }
    }

    // El front lo llama solo cuando el access token ya vencio (ver
    // authFetch() en el front) -- el refresh token viaja SOLO en la cookie
    // httpOnly (el navegador la manda solo, el JS del front nunca la lee
    // ni la puede leer), nunca en el body ni en un header manual

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@CookieValue(value = COOKIE_REFRESH, required = false) String refreshTokenValue) {
        if (refreshTokenValue == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Sin sesión activa");
        }
        try {
            LoginResult resultado = authService.refrescar(refreshTokenValue);
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, cookieRefresh(resultado.refreshToken()).toString())
                    .body(new AuthResponse(resultado.accessToken(), resultado.type()));
        } catch (AuthenticationException e) {
            // Token vencido/revocado/reusado -- se limpia la cookie vieja
            // para que el front no siga reintentando con algo que ya no
            // sirve, y que quede claro que hay que loguearse de nuevo
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE, cookieVacia().toString())
                    .body("Sesión inválida, iniciá sesión de nuevo");
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(value = COOKIE_REFRESH, required = false) String refreshTokenValue) {
        if (refreshTokenValue != null) {
            authService.cerrarSesion(refreshTokenValue);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookieVacia().toString())
                .build();
    }

    private ResponseCookie cookieRefresh(String valor) {
        return ResponseCookie.from(COOKIE_REFRESH, valor)
                .httpOnly(true)
                // TODO: pasar a true apenas la VM sirva por HTTPS -- con
                // Secure=true y sin TLS, el navegador directamente descarta
                // la cookie y el refresh nunca funcionaria.
                .secure(false)
                .sameSite("Strict")
                // Acotada a /auth: nunca viaja en requests a compra-service,
                // solo a este servicio (login/refresh/logout).
                .path("/auth")
                .maxAge(DURACION_REFRESH)
                .build();
    }

    private ResponseCookie cookieVacia() {
        return ResponseCookie.from(COOKIE_REFRESH, "")
                .httpOnly(true)
                .secure(false)
                .sameSite("Strict")
                .path("/auth")
                .maxAge(0)
                .build();
    }
}
