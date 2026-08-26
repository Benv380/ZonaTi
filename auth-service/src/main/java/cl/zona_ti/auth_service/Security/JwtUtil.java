package cl.zona_ti.auth_service.Security;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Base64;
import java.util.Date;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import cl.zona_ti.auth_service.Model.User;
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

    public String generateToken(User user) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(user.getUsername())
                .claim("role", user.getRole())
                .issuedAt(now)
                .expiration(expiration)
                .signWith(signingKey)
                .compact();
    }
}
