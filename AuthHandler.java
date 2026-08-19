package medtour;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * POST /api/auth/register    -> { fullName, email, password, phone, country, role: PATIENT|DOCTOR, ...doctor fields }
 * POST /api/auth/login       -> { email, password } -> { token, fullName, email, role, emailVerified }
 * GET  /api/auth/verify-email?token=...  -> public
 */
public class AuthHandler implements HttpHandler {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (Http.handleCors(ex)) return;
        try {
            List<String> segments = Http.pathSegmentsAfter(ex, "/api/auth");
            String method = ex.getRequestMethod();

            if (method.equalsIgnoreCase("POST") && !segments.isEmpty() && segments.get(0).equals("register")) {
                Map<String, String> body = Json.parseFlatObject(Http.readBody(ex));
                Http.sendObject(ex, 201, register(body));
                return;
            }
            if (method.equalsIgnoreCase("POST") && !segments.isEmpty() && segments.get(0).equals("login")) {
                Map<String, String> body = Json.parseFlatObject(Http.readBody(ex));
                Http.sendObject(ex, 200, login(body));
                return;
            }
            if (method.equalsIgnoreCase("GET") && !segments.isEmpty() && segments.get(0).equals("verify-email")) {
                Map<String, String> q = Http.parseQuery(ex.getRequestURI().getRawQuery());
                String token = q.get("token");
                if (token == null || token.isBlank()) {
                    throw new ApiException(400, "Missing required parameter: token.");
                }
                verifyEmail(token);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("success", true);
                out.put("message", "Your email address has been verified.");
                Http.sendObject(ex, 200, out);
                return;
            }
            throw new ApiException(404, "Not found.");
        } catch (Throwable t) {
            Http.sendApiError(ex, t);
        }
    }

    // ---------------------------------------------------------------- Register

    private static Map<String, Object> register(Map<String, String> body) {
        String fullName = body.get("fullName");
        String email = body.get("email");
        String password = body.get("password");
        String phone = body.get("phone");
        String country = body.get("country");
        String role = body.get("role");
        Integer hospitalId = Validate.optionalInt(body.get("hospitalId"));
        String specialization = body.get("specialization");
        Integer experienceYears = Validate.optionalInt(body.get("experienceYears"));
        BigDecimal consultationFeeInr = parseBigDecimalOrNull(body.get("consultationFeeInr"));

        ValidationErrors errors = new ValidationErrors();
        errors.require(fullName, "fullName", "Full name is required.");
        errors.maxLength(fullName, 150, "fullName", "Full name is too long.");
        errors.email(email, "email");
        errors.maxLength(email, 150, "email", "Email is too long.");
        errors.password(password, "password");
        errors.phone(phone, "phone");
        errors.require(country, "country", "Country is required.");
        errors.maxLength(country, 80, "country", "Country name is too long.");
        errors.require(role, "role", "Role is required.");
        if (role != null && !Set.of("PATIENT", "DOCTOR", "ADMIN").contains(role)) {
            errors.custom(true, "role", "Role is required.");
        }
        errors.maxLength(specialization, 150, "specialization", "Specialization is too long.");
        errors.range(experienceYears, 0, 80, "experienceYears", "Please enter a realistic number of years.");
        errors.throwIfInvalid();

        if ("ADMIN".equals(role)) {
            throw new ApiException(403, "Admin accounts cannot be self-registered.");
        }

        String normalizedEmail = email.trim().toLowerCase();
        if (emailExists(normalizedEmail)) {
            throw new ApiException(409, "An account with this email already exists.");
        }

        String cleanFullName = TextSanitizer.stripTags(fullName.trim());
        String cleanCountry = TextSanitizer.stripTags(country.trim());
        String passwordHash = PasswordUtil.hash(password);

        String rawToken = generateRawToken();
        String tokenHash = hashToken(rawToken);
        LocalDateTime tokenExpiry = LocalDateTime.now().plusHours(Config.VERIFICATION_TOKEN_EXPIRY_HOURS);

        int userId;
        try (Connection conn = Database.getConnection()) {
            String sql = "INSERT INTO users (full_name, email, password_hash, phone, country, role, " +
                    "email_verified, verification_token_hash, verification_token_expires_at) " +
                    "VALUES (?,?,?,?,?,?,FALSE,?,?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, cleanFullName);
                ps.setString(2, normalizedEmail);
                ps.setString(3, passwordHash);
                ps.setString(4, phone.trim());
                ps.setString(5, cleanCountry);
                ps.setString(6, role);
                ps.setString(7, tokenHash);
                ps.setTimestamp(8, Timestamp.valueOf(tokenExpiry));
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    userId = keys.getInt(1);
                }
            }
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }

        if ("DOCTOR".equals(role)) {
            if (hospitalId == null || specialization == null || specialization.isBlank()) {
                throw new ApiException(400, "Doctor registration requires hospitalId and specialization.");
            }
            Map<String, Object> hospital = findHospital(hospitalId);
            if (hospital == null) throw new ApiException(400, "Selected hospital was not found.");

            String cleanSpecialization = TextSanitizer.stripTags(specialization.trim());
            BigDecimal fee = consultationFeeInr == null ? BigDecimal.ZERO : consultationFeeInr;
            int expYears = experienceYears == null ? 0 : experienceYears;

            // If a doctor row already exists with this name at this hospital and no login attached
            // (e.g. sample/seed data, or a profile an admin added by hand), claim it instead of
            // inserting a duplicate row — otherwise appointments booked against the original entry
            // would never match this new account's doctor id, and the booking dropdown would show
            // the same doctor twice.
            try (Connection conn = Database.getConnection()) {
                Integer existingDoctorId = null;
                try (PreparedStatement find = conn.prepareStatement(
                        "SELECT id FROM doctors WHERE hospital_id = ? AND LOWER(name) = LOWER(?) AND user_id IS NULL")) {
                    find.setInt(1, hospitalId);
                    find.setString(2, cleanFullName);
                    try (ResultSet rs = find.executeQuery()) {
                        if (rs.next()) existingDoctorId = rs.getInt("id");
                    }
                }

                if (existingDoctorId != null) {
                    try (PreparedStatement update = conn.prepareStatement(
                            "UPDATE doctors SET user_id = ?, specialization = ?, experience_years = ?, consultation_fee_inr = ? WHERE id = ?")) {
                        update.setInt(1, userId);
                        update.setString(2, cleanSpecialization);
                        update.setInt(3, expYears);
                        update.setBigDecimal(4, fee);
                        update.setInt(5, existingDoctorId);
                        update.executeUpdate();
                    }
                } else {
                    try (PreparedStatement insert = conn.prepareStatement(
                            "INSERT INTO doctors (hospital_id, user_id, name, specialization, experience_years, consultation_fee_inr) VALUES (?,?,?,?,?,?)")) {
                        insert.setInt(1, hospitalId);
                        insert.setInt(2, userId);
                        insert.setString(3, cleanFullName);
                        insert.setString(4, cleanSpecialization);
                        insert.setInt(5, expYears);
                        insert.setBigDecimal(6, fee);
                        insert.executeUpdate();
                    }
                }
            } catch (Exception e) {
                throw new ApiException(500, "Something went wrong on our end. Please try again.");
            }
        }

        String token = JwtUtil.generateToken(userId, normalizedEmail, "ROLE_" + role);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("fullName", cleanFullName);
        out.put("email", normalizedEmail);
        out.put("role", role);
        out.put("emailVerified", false);
        return out;
    }

    // ---------------------------------------------------------------- Login

    private static Map<String, Object> login(Map<String, String> body) {
        ValidationErrors errors = new ValidationErrors();
        String email = body.get("email");
        String password = body.get("password");
        errors.email(email, "email");
        errors.require(password, "password", "Password is required.");
        errors.throwIfInvalid();

        String normalizedEmail = email.trim().toLowerCase();

        if (RateLimiter.isBlocked(normalizedEmail)) {
            throw new ApiException(429, "Too many failed login attempts for this account. Please try again in a few minutes.");
        }

        Map<String, Object> user = findUserByEmail(normalizedEmail);
        if (user == null || !PasswordUtil.matches(password, (String) user.get("passwordHash"))) {
            RateLimiter.recordFailure(normalizedEmail);
            throw new ApiException(401, "Incorrect email or password.");
        }

        RateLimiter.recordSuccess(normalizedEmail);

        String token = JwtUtil.generateToken((Integer) user.get("id"), normalizedEmail, "ROLE_" + user.get("role"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("fullName", user.get("fullName"));
        out.put("email", normalizedEmail);
        out.put("role", user.get("role"));
        out.put("emailVerified", user.get("emailVerified"));
        return out;
    }

    // ---------------------------------------------------------------- Verify email

    private static void verifyEmail(String rawToken) {
        String hash = hashToken(rawToken.trim());
        try (Connection conn = Database.getConnection()) {
            PreparedStatement find = conn.prepareStatement(
                    "SELECT id, verification_token_expires_at FROM users WHERE verification_token_hash = ?");
            find.setString(1, hash);
            int userId;
            try (ResultSet rs = find.executeQuery()) {
                if (!rs.next()) throw new ApiException(400, "This verification link is invalid or has expired.");
                Timestamp expiry = rs.getTimestamp("verification_token_expires_at");
                if (expiry == null || expiry.toLocalDateTime().isBefore(LocalDateTime.now())) {
                    throw new ApiException(400, "This verification link is invalid or has expired.");
                }
                userId = rs.getInt("id");
            }
            try (PreparedStatement update = conn.prepareStatement(
                    "UPDATE users SET email_verified = TRUE, verification_token_hash = NULL, verification_token_expires_at = NULL WHERE id = ?")) {
                update.setInt(1, userId);
                update.executeUpdate();
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
    }

    // ---------------------------------------------------------------- Helpers

    private static boolean emailExists(String email) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
    }

    private static Map<String, Object> findUserByEmail(String email) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, full_name, email, password_hash, role, email_verified FROM users WHERE email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getInt("id"));
                m.put("fullName", rs.getString("full_name"));
                m.put("email", rs.getString("email"));
                m.put("passwordHash", rs.getString("password_hash"));
                m.put("role", rs.getString("role"));
                m.put("emailVerified", rs.getBoolean("email_verified"));
                return m;
            }
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
    }

    private static Map<String, Object> findHospital(int id) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id, name FROM hospitals WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getInt("id"));
                m.put("name", rs.getString("name"));
                return m;
            }
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static BigDecimal parseBigDecimalOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
