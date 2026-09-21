package cl.zona_ti.licitacion_service.Security;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Base64;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;

// compra-service NO emite tokens (eso es exclusivo de auth-service, ver
// JwtUtil.generateToken alli) -- esta es una copia deliberada, recortada
// a solo lo necesario para VALIDAR la firma de un token ya emitido, con el
// mismo JWT_SECRET compartido (variable de entorno, ver
// docker-compose.yml). Ver Alcance.java para el porque de la duplicacion
// de codigo entre servicios.
@Component
public class JwtUtil {

    private static final int MIN_SECRET_BYTES = 32;

    private final String secret;
    private Key signingKey;

    public JwtUtil(@Value("${jwt.secret}") String secret) {
        this.secret = secret;
    }

    @PostConstruct
    void init() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret no esta configurado. Tiene que ser EXACTAMENTE el mismo " +
                    "JWT_SECRET que usa auth-service, si no ningun token va a validar.");
        }

        byte[] keyBytes = decodeSecret(secret);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret es demasiado debil (" + keyBytes.length + " bytes). " +
                    "Se requieren al menos " + MIN_SECRET_BYTES + " bytes (256 bits) para HS256.");
        }

        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    private byte[] decodeSecret(String rawSecret) {
        try {
            return Base64.getDecoder().decode(rawSecret);
        } catch (IllegalArgumentException notBase64) {
            return rawSecret.getBytes(StandardCharsets.UTF_8);
        }
    }

    // null si el token es invalido/expirado -- JwtAuthenticationFilter deja
    // la request como no autenticada en ese caso, en vez de tirar un 500.
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
