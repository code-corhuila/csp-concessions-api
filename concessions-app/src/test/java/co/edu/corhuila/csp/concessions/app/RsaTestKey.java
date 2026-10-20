package co.edu.corhuila.csp.concessions.app;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * A throwaway RSA key pair for the tests: no key material of the service is committed to this
 * repository, and none is needed from {@code csp-infra}. The PEM is exactly what
 * {@code JWT_PUBLIC_KEY} would carry in a real environment.
 */
final class RsaTestKey {

    private final KeyPair keyPair;

    private RsaTestKey(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    static RsaTestKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return new RsaTestKey(generator.generateKeyPair());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("the test key pair could not be generated", exception);
        }
    }

    String publicKeyPem() {
        String base64 = Base64.getEncoder().encodeToString(publicKey().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
    }

    RSAPublicKey publicKey() {
        return (RSAPublicKey) keyPair.getPublic();
    }

    /**
     * A token signed with this pair for the caller of a test: exactly what {@code dev-token.sh}
     * of {@code csp-infra} hands out until the identity service exists.
     */
    String token(String subject) {
        return token(subject, List.of());
    }

    /** The same token with the permissions claim that the identity service puts in it. */
    String token(String subject, List<String> permissions) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .claim("permissions", permissions)
                    .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("the token of the test could not be signed", exception);
        }
    }
}
