package cl.zona_ti.auth_service.Security;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Base64;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import cl.zona_ti.auth_service.Model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;

@Component
public class JwtUtil {

    // Minimo exigido por HS256 (256 bits). El secreto real viene siempre de
    // fuera del codigo fuente (variable de entorno JWT_SECRET) -- ver
    // application.yml. El unico valor que vive en el repo es un default de
    // desarrollo local, generado al azar, que nunca debe usarse en produccion.
    private static final int MIN_SECRET_BYTES = 32;

    private final String secret;
    private final long expirationMs;
    private Key signingKey;

    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms:3600000}") long expirationMs
    ) {
        this.secret = secret;
        this.expirationMs = expirationMs;
    }

    @PostConstruct
    void init() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret no esta configurado. Define la variable de entorno JWT_SECRET " +
                    "(minimo 32 bytes / 256 bits, ej. `openssl rand -base64 64`).");
        }

        byte[] keyBytes = decodeSecret(secret);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret es demasiado debil (" + keyBytes.length + " bytes). " +
                    "Se requieren al menos " + MIN_SECRET_BYTES + " bytes (256 bits) para HS256.");
        }

        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    //
    //   Acepta el secreto tanto en Base64 (recomendado, ej. `openssl rand -base64 64`)
    //   como en texto plano, para no romper configuraciones existentes.
    //
    private byte[] decodeSecret(String rawSecret) {
        try {
            return Base64.getDecoder().decode(rawSecret);
        } catch (IllegalArgumentException notBase64) {
            return rawSecret.getBytes(StandardCharsets.UTF_8);
        }
    }

    // El token lleva "alcance" (no solo el nombre del rol) para que quien
    // lo consuma -- este mismo servicio, o mas adelante compra-service --
    // pueda chequear permisos sin ir a la base de nuevo (ver Alcance.java).
    // "empresaId" solo se agrega si el usuario pertenece a una (los roles
    // GLOBAL no tienen empresa).
    public String generateToken(User user) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expirationMs);

        var builder = Jwts.builder()
                .subject(user.getUsername())
                .claim("role", user.getRole().getNombre())
                .claim("alcance", user.getRole().getAlcance().name())
                .issuedAt(now)
                .expiration(expiration);

        if (user.getEmpresa() != null) {
            builder.claim("empresaId", user.getEmpresa().getId());
        }

        return builder.signWith(signingKey).compact();
    }

    // Valida la firma/expiracion y devuelve los claims -- null si el token
    // es invalido o expiro (JwtAuthenticationFilter decide que hacer con
    // eso, normalmente dejar la request como no autenticada en vez de
    // tirar un 500).
    public Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith((SecretKey) signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException invalid) {
            return null;
        }
    }
}
