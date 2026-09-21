package cl.zona_ti.auth_service.Security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Cifrado simetrico (AES-256-GCM) para datos sensibles que hay que poder
// RECUPERAR en texto plano mas adelante (a diferencia de una contraseña de
// login, que se hashea con BCrypt y nunca se descifra) -- hoy el unico uso
// es Empresa.chileCompraPasswordCifrada (credenciales de Chile Compra para
// el flujo de Power Automate), pero queda genérico por si hace falta cifrar
// algo mas en el futuro.
//
// GCM en vez de un modo simple (ej. CBC) porque es autenticado: si alguien
// toca el valor cifrado en la base (a mano o por corrupcion), descifrar
// tira una excepcion en vez de devolver basura en silencio.
//
// La clave NUNCA vive en la base de datos ni en este codigo -- sale de
// CHILECOMPRA_ENCRYPTION_KEY (variable de entorno, sin default a proposito,
// mismo criterio que JWT_SECRET). Se genera con:
//   openssl rand -base64 32
// (32 bytes = 256 bits, el tamaño que exige AES-256). Guardar esa clave
// aparte de la base es lo que hace que un volcado de la base SOLA no
// alcance para recuperar las contraseñas -- hace falta ademas la clave,
// que vive en el .env de la VM (o, mas adelante, en Azure Key Vault).
@Component
public class CifradoAesService {

    private static final String ALGORITMO = "AES/GCM/NoPadding";
    private static final int TAMANO_TAG_BITS = 128;
    private static final int TAMANO_IV_BYTES = 12;

    private final SecretKeySpec clave;
    private final SecureRandom random = new SecureRandom();

    public CifradoAesService(@Value("${chilecompra.encryption-key}") String claveBase64) {
        byte[] claveBytes;
        try {
            claveBytes = Base64.getDecoder().decode(claveBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "CHILECOMPRA_ENCRYPTION_KEY no es base64 valido -- generar con: openssl rand -base64 32", e);
        }
        if (claveBytes.length != 32) {
            throw new IllegalStateException(
                    "CHILECOMPRA_ENCRYPTION_KEY debe decodificar a 32 bytes (AES-256) -- generar con: openssl rand -base64 32");
        }
        this.clave = new SecretKeySpec(claveBytes, "AES");
    }

    // IV aleatorio distinto en cada llamada (nunca reusar un IV con la
    // misma clave -- rompe las garantias de seguridad de GCM), va
    // prepended al resultado para no necesitar una columna aparte.
    public String cifrar(String textoPlano) {
        try {
            byte[] iv = new byte[TAMANO_IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITMO);
            cipher.init(Cipher.ENCRYPT_MODE, clave, new GCMParameterSpec(TAMANO_TAG_BITS, iv));
            byte[] cifrado = cipher.doFinal(textoPlano.getBytes(StandardCharsets.UTF_8));

            byte[] resultado = new byte[iv.length + cifrado.length];
            System.arraycopy(iv, 0, resultado, 0, iv.length);
            System.arraycopy(cifrado, 0, resultado, iv.length, cifrado.length);
            return Base64.getEncoder().encodeToString(resultado);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cifrar el valor", e);
        }
    }

    public String descifrar(String valorCifrado) {
        try {
            byte[] datos = Base64.getDecoder().decode(valorCifrado);
            byte[] iv = new byte[TAMANO_IV_BYTES];
            byte[] cifrado = new byte[datos.length - TAMANO_IV_BYTES];
            System.arraycopy(datos, 0, iv, 0, TAMANO_IV_BYTES);
            System.arraycopy(datos, TAMANO_IV_BYTES, cifrado, 0, cifrado.length);

            Cipher cipher = Cipher.getInstance(ALGORITMO);
            cipher.init(Cipher.DECRYPT_MODE, clave, new GCMParameterSpec(TAMANO_TAG_BITS, iv));
            byte[] textoPlano = cipher.doFinal(cifrado);
            return new String(textoPlano, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo descifrar el valor -- ¿cambio la clave, o el dato esta corrupto?", e);
        }
    }
}
