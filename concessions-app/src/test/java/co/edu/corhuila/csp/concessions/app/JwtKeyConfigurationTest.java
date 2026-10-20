package co.edu.corhuila.csp.concessions.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JwtKeyConfigurationTest {

    private final JwtKeyConfiguration configuration = new JwtKeyConfiguration();
    private final RsaTestKey key = RsaTestKey.generate();

    @Test
    void thePublicKeyComesFromTheInlinePem() {
        assertEquals(key.publicKey(), configuration.jwtPublicKey(key.publicKeyPem(), ""));
    }

    @Test
    void thePublicKeyComesFromTheFileWhenTheInlinePemIsAbsent(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("jwt-public.pem");
        Files.writeString(file, key.publicKeyPem());

        assertEquals(key.publicKey(), configuration.jwtPublicKey("", file.toString()));
    }

    @Test
    void withoutAnyOfTheTwoTheServiceRefusesToStart() {
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> configuration.jwtPublicKey("", ""));

        assertTrue(failure.getMessage().contains("JWT_PUBLIC_KEY"));
    }

    @Test
    void aKeyThatIsNotAKeyIsRejectedWithAClearMessage() {
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> configuration.jwtPublicKey("not a key", ""));

        assertTrue(failure.getMessage().contains("JWT_PUBLIC_KEY"));
    }

    @Test
    void aFileThatCannotBeReadIsRejectedWithAClearMessage() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> configuration.jwtPublicKey("", Path.of("there-is-no-such-file.pem").toString()));

        assertTrue(failure.getMessage().contains("JWT_PUBLIC_KEY_FILE"));
    }
}
