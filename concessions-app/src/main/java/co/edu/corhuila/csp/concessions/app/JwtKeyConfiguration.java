package co.edu.corhuila.csp.concessions.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public key every token of this service is validated with (Norma 5.3.7). It comes from the
 * environment: {@code JWT_PUBLIC_KEY} carries the PEM itself and {@code JWT_PUBLIC_KEY_FILE} the
 * path of the file the platform mounts. One of the two is required; the service never holds a
 * private key nor a shared secret. Configuration is read here, in the composition root, and
 * nowhere else.
 */
@Configuration
public class JwtKeyConfiguration {

    private static final String BEGIN = "-----BEGIN PUBLIC KEY-----";
    private static final String END = "-----END PUBLIC KEY-----";

    @Bean
    RSAPublicKey jwtPublicKey(
            @Value("${jwt.public-key:}") String inlinePem,
            @Value("${jwt.public-key-file:}") String file) {
        String pem = !inlinePem.isBlank() ? inlinePem.replace("\\n", "\n") : read(file);
        if (pem.isBlank()) {
            throw new IllegalStateException(
                    "jwt.public-key (JWT_PUBLIC_KEY) or jwt.public-key-file (JWT_PUBLIC_KEY_FILE) is required");
        }
        return parse(pem);
    }

    private String read(String file) {
        if (file == null || file.isBlank()) {
            return "";
        }
        try {
            return Files.readString(Path.of(file));
        } catch (Exception exception) {
            throw new IllegalStateException("jwt.public-key-file (JWT_PUBLIC_KEY_FILE) cannot be read", exception);
        }
    }

    private RSAPublicKey parse(String pem) {
        try {
            String base64 = pem.replace(BEGIN, "").replace(END, "").replaceAll("\\s", "");
            byte[] encoded = Base64.getDecoder().decode(base64);
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception exception) {
            throw new IllegalStateException("jwt.public-key (JWT_PUBLIC_KEY) is not an RSA public key", exception);
        }
    }
}
