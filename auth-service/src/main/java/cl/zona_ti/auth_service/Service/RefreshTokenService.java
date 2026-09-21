package cl.zona_ti.auth_service.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import cl.zona_ti.auth_service.Model.RefreshToken;
import cl.zona_ti.auth_service.Model.User;
import cl.zona_ti.auth_service.Repository.RefreshTokenRepository;

// Refresh tokens: opacos (random, no JWT), guardados en BD para poder
// revocarlos -- a diferencia del access token (JWT autocontenido, dura 1h,
// nunca se persiste), este dura mas (7 dias) y es lo que le permite al
// front pedir un access token nuevo sin volver a escribir usuario/
// contraseña (ver AuthController.refresh).
@Service
public class RefreshTokenService {

    private static final long DURACION_DIAS = 7;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    public String crear(User usuario) {
        // 384 bits de entropia -- no es un JWT, es solo un identificador
        // opaco imposible de adivinar; toda la info (a quien pertenece,
        // si esta vigente) vive en la fila de la BD, no en el token mismo.
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        RefreshToken entidad = new RefreshToken();
        entidad.setUsuario(usuario);
        entidad.setToken(token);
        entidad.setExpiraEn(LocalDateTime.now().plusDays(DURACION_DIAS));
        entidad.setRevocado(false);
        refreshTokenRepository.save(entidad);

        return token;
    }

    // Valida que el token exista, no este vencido ni ya revocado, y de
    // paso lo revoca -- ROTACION: un refresh token solo sirve una vez.
    // Si alguien intenta reusar uno ya rotado (por ejemplo, porque se lo
    // robaron y el dueño legitimo ya lo uso), esta llamada falla igual
    // que si estuviera vencido, cortando la sesion robada tambien.
    public User validarYRotar(String token) {
        RefreshToken entidad = refreshTokenRepository.findByToken(token)
                .orElseThrow(() -> new BadCredentialsException("Refresh token inválido"));

        if (Boolean.TRUE.equals(entidad.getRevocado()) || entidad.getExpiraEn().isBefore(LocalDateTime.now())) {
            throw new BadCredentialsException("Refresh token vencido o revocado");
        }

        entidad.setRevocado(true);
        refreshTokenRepository.save(entidad);

        return entidad.getUsuario();
    }

    // Logout: revoca el token de forma explicita para que no se pueda
    // volver a usar aunque todavia no haya vencido. Si el token no existe
    // (ya vencido/borrado/invalido), no hace nada -- el logout igual debe
    // "funcionar" del lado del cliente.
    public void revocar(String token) {
        refreshTokenRepository.findByToken(token).ifPresent(entidad -> {
            entidad.setRevocado(true);
            refreshTokenRepository.save(entidad);
        });
    }
}
