package medtour;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * Password hashing with zero external dependencies — everything here is built into the JDK
 * (javax.crypto), so no BCrypt library/jar is needed on the classpath.
 *
 * Uses PBKDF2WithHmacSHA256 (NIST-recommended, part of the JDK's own crypto providers) with a
 * random 16-byte salt and 100,000 iterations. Stored format:
 *   pbkdf2_sha256$<iterations>$<saltBase64>$<hashBase64>
 * so the iteration count travels with the hash and can be raised later without breaking existing
 * rows (matches() just reads whatever count is stored).
 */
public class PasswordUtil {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 100_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private PasswordUtil() {}

    public static String hash(String plainPassword) {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(plainPassword.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS);
        return "pbkdf2_sha256$" + ITERATIONS + "$" +
                Base64.getEncoder().encodeToString(salt) + "$" +
                Base64.getEncoder().encodeToString(hash);
    }

    public static boolean matches(String plainPassword, String stored) {
        if (stored == null) return false;
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 4 || !parts[0].equals("pbkdf2_sha256")) return false;
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(plainPassword.toCharArray(), salt, iterations, expected.length * 8);
            return constantTimeEquals(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyLengthBits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLengthBits);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            // PBKDF2WithHmacSHA256 is a standard JDK algorithm — unreachable in practice.
            throw new IllegalStateException("PBKDF2 not available", e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) result |= a[i] ^ b[i];
        return result == 0;
    }
}
