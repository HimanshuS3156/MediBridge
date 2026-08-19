package medtour;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal, dependency-free JWT (HS256) implementation — everything here is built into the JDK
 * (javax.crypto), so no jjwt/Nimbus library is needed on the classpath. Same token shape a real
 * JWT library would produce (header.payload.signature, base64url, HMAC-SHA256).
 *
 * IMPORTANT: change the secret before deploying anywhere real — set the MEDTOUR_JWT_SECRET env
 * var (a base64-encoded 256-bit+ value; generate one with `openssl rand -base64 32`).
 */
public class JwtUtil {

    private static final String SECRET_B64 = System.getenv().getOrDefault(
            "MEDTOUR_JWT_SECRET", "c2hhbmdlLXRoaXMtc2VjcmV0LWJlZm9yZS1wcm9kdWN0aW9uLXVzZS1vbmx5LWZvci1kZXY=");
    private static final long EXPIRATION_MS = Long.parseLong(
            System.getenv().getOrDefault("MEDTOUR_JWT_EXPIRY", "86400000")); // 24h default

    private static final Base64.Encoder B64URL_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();

    public static class Claims {
        public final Integer userId;
        public final String email;
        public final String role; // e.g. "ROLE_PATIENT"

        public Claims(Integer userId, String email, String role) {
            this.userId = userId;
            this.email = email;
            this.role = role;
        }
    }

    public static String generateToken(int userId, String email, String role) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        long now = System.currentTimeMillis();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", email);
        payload.put("uid", userId);
        payload.put("role", role);
        payload.put("iat", now / 1000);
        payload.put("exp", (now + EXPIRATION_MS) / 1000);

        String headerB64 = B64URL_ENC.encodeToString(Json.write(header).getBytes(StandardCharsets.UTF_8));
        String payloadB64 = B64URL_ENC.encodeToString(Json.write(payload).getBytes(StandardCharsets.UTF_8));
        String signingInput = headerB64 + "." + payloadB64;
        String signature = sign(signingInput);
        return signingInput + "." + signature;
    }

    /** Returns the verified claims, or null if the token is missing, malformed, badly signed, or expired. */
    public static Claims verify(String token) {
        if (token == null) return null;
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null;

        String signingInput = parts[0] + "." + parts[1];
        String expectedSignature = sign(signingInput);
        if (!constantTimeEquals(expectedSignature, parts[2])) return null;

        try {
            String payloadJson = new String(B64URL_DEC.decode(parts[1]), StandardCharsets.UTF_8);
            Map<String, String> claims = Json.parseFlatObject(payloadJson);

            String expStr = claims.get("exp");
            if (expStr == null) return null;
            long exp = Long.parseLong(expStr);
            if (System.currentTimeMillis() / 1000 > exp) return null; // expired

            String email = claims.get("sub");
            String role = claims.get("role");
            Integer userId = Http.parseIntOrNull(claims.get("uid"));
            if (email == null || role == null) return null;
            return new Claims(userId, email, role);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sign(String data) {
        try {
            byte[] secretBytes = Base64.getDecoder().decode(SECRET_B64);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            byte[] sig = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return B64URL_ENC.encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign JWT", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int result = 0;
        for (int i = 0; i < x.length; i++) result |= x[i] ^ y[i];
        return result == 0;
    }
}
