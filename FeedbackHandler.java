package medtour;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * POST /api/feedback -> anyone submits name, email, 1-5 star rating, and a comment
 * GET  /api/feedback -> ADMIN only
 */
public class FeedbackHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (Http.handleCors(ex)) return;
        try {
            String method = ex.getRequestMethod();
            if (method.equalsIgnoreCase("POST")) {
                Map<String, String> body = Json.parseFlatObject(Http.readBody(ex));
                submit(body);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("success", true);
                out.put("message", "Thank you! Your feedback helps us improve the healthcare experience.");
                Http.sendObject(ex, 201, out);
                return;
            }
            if (method.equalsIgnoreCase("GET")) {
                AuthContext.requireRole(ex, "ADMIN");
                Http.sendObject(ex, 200, listAll());
                return;
            }
            throw new ApiException(405, "This HTTP method isn't supported for this endpoint.");
        } catch (Throwable t) {
            Http.sendApiError(ex, t);
        }
    }

    private static void submit(Map<String, String> body) {
        String name = body.get("name");
        String email = body.get("email");
        Integer rating = Validate.optionalInt(body.get("rating"));
        String comment = body.get("comment");
        Integer appointmentId = Validate.optionalInt(body.get("appointmentId"));

        ValidationErrors errors = new ValidationErrors();
        errors.require(name, "name", "Name is required.");
        errors.maxLength(name, 150, "name", "Name is too long.");
        errors.email(email, "email");
        errors.requiredInt(rating, "rating", "Rating is required.");
        errors.range(rating, 1, 5, "rating", "Rating must be between 1 and 5.");
        errors.require(comment, "comment", "Feedback comment is required.");
        errors.maxLength(comment, 2000, "comment", "Feedback is too long.");
        errors.throwIfInvalid();

        if (appointmentId != null && !appointmentExists(appointmentId)) {
            throw new ApiException(400, "That appointment reference could not be found.");
        }

        String cleanComment = TextSanitizer.stripTags(comment.trim());

        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO feedback (name, email, appointment_id, rating, comment) VALUES (?,?,?,?,?)")) {
            ps.setString(1, name.trim());
            ps.setString(2, email.trim());
            if (appointmentId != null) ps.setInt(3, appointmentId); else ps.setNull(3, java.sql.Types.INTEGER);
            ps.setInt(4, rating);
            ps.setString(5, cleanComment);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
    }

    private static boolean appointmentExists(int id) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM appointments WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
    }

    private static List<Object> listAll() {
        List<Object> out = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, name, email, rating, comment, appointment_id, created_at FROM feedback ORDER BY created_at DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getInt("id"));
                m.put("name", rs.getString("name"));
                m.put("email", rs.getString("email"));
                m.put("rating", rs.getInt("rating"));
                m.put("comment", rs.getString("comment"));
                int apptId = rs.getInt("appointment_id");
                m.put("appointmentId", rs.wasNull() ? null : apptId);
                Timestamp createdAt = rs.getTimestamp("created_at");
                m.put("createdAt", createdAt != null ? createdAt.toLocalDateTime() : null);
                out.add(m);
            }
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
        return out;
    }
}
