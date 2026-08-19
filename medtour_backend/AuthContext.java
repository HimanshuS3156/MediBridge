package medtour;

import com.sun.net.httpserver.HttpExchange;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Reads "Authorization: Bearer <jwt>", verifies it, and re-fetches the user row from MySQL so
 * every request sees a fresh role/email-verified status rather than trusting a possibly-stale JWT
 * claim. This is the plain-Java equivalent of Spring Security's JwtAuthFilter + SecurityContext +
 * CustomUserDetailsService rolled into one small helper, called explicitly at the top of any
 * handler that needs auth (there's no filter chain here — each handler is responsible for calling
 * requireUser()/requireRole() itself, same as it validates any other input).
 */
public class AuthContext {

    public static class CurrentUser {
        public final int id;
        public final String fullName;
        public final String email;
        public final String role; // PATIENT, DOCTOR, ADMIN
        public final boolean emailVerified;

        public CurrentUser(int id, String fullName, String email, String role, boolean emailVerified) {
            this.id = id;
            this.fullName = fullName;
            this.email = email;
            this.role = role;
            this.emailVerified = emailVerified;
        }
    }

    /** Returns the current user, or null if there's no valid token. Never throws. */
    public static CurrentUser currentUserOrNull(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        String token = header.substring(7).trim();

        JwtUtil.Claims claims = JwtUtil.verify(token);
        if (claims == null) return null;

        try (Connection conn = Database.getConnection()) {
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, full_name, email, role, email_verified FROM users WHERE email = ?");
            ps.setString(1, claims.email);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new CurrentUser(
                        rs.getInt("id"),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("role"),
                        rs.getBoolean("email_verified"));
            }
        } catch (SQLException e) {
            System.err.println("Failed to resolve current user: " + e.getMessage());
            return null;
        }
    }

    /** Throws 401 if there's no valid token — matches RestAuthenticationEntryPoint's message/shape. */
    public static CurrentUser requireUser(HttpExchange ex) {
        CurrentUser user = currentUserOrNull(ex);
        if (user == null) {
            throw new ApiException(401, "Please log in to continue.");
        }
        return user;
    }

    /** Throws 401/403 if there's no valid token or the role doesn't match — matches
     *  RestAccessDeniedHandler's message/shape for the role-mismatch case. */
    public static CurrentUser requireRole(HttpExchange ex, String role) {
        CurrentUser user = requireUser(ex);
        if (!user.role.equals(role)) {
            throw new ApiException(403, "You don't have permission to perform this action.");
        }
        return user;
    }
}
