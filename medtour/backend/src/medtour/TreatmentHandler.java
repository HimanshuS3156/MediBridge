package medtour;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /api/treatments                  -> all treatments
 * GET /api/treatments?category=Dental  -> filter by category
 * GET /api/treatments?q=knee           -> search by name/description
 * GET /api/treatments/{id}             -> single treatment detail
 */
public class TreatmentHandler implements HttpHandler {

    private static final String BASE_SELECT =
            "SELECT t.id, t.name, t.category, t.description, t.cost_min, t.cost_max, t.duration_days, " +
            "h.id AS hospital_id, h.name AS hospital_name, h.city AS hospital_city " +
            "FROM treatments t JOIN hospitals h ON t.hospital_id = h.id";

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (Http.handleCors(ex)) return;
        try {
            if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                throw new ApiException(405, "This HTTP method isn't supported for this endpoint.");
            }
            List<String> segments = Http.pathSegmentsAfter(ex, "/api/treatments");
            if (segments.isEmpty()) {
                Map<String, String> q = Http.parseQuery(ex.getRequestURI().getRawQuery());
                Http.sendObject(ex, 200, search(q.get("category"), q.get("q")));
            } else {
                int id = Http.requirePathInt(segments.get(0), "id");
                Http.sendObject(ex, 200, getOne(id));
            }
        } catch (Throwable t) {
            Http.sendApiError(ex, t);
        }
    }

    static List<Object> search(String category, String q) {
        String categoryFilter = blankToNull(category);
        String search = blankToNull(q);

        StringBuilder sql = new StringBuilder(BASE_SELECT).append(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (categoryFilter != null) {
            sql.append(" AND t.category = ?");
            params.add(categoryFilter);
        }
        if (search != null) {
            sql.append(" AND (LOWER(t.name) LIKE LOWER(?) OR LOWER(t.description) LIKE LOWER(?))");
            params.add("%" + search + "%");
            params.add("%" + search + "%");
        }
        sql.append(" ORDER BY t.name ASC");

        List<Object> out = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(toMap(rs));
            }
        } catch (Exception e) { 
            System.err.println("TreatmentHandler failed: " + e); e.printStackTrace(); 
            throw new ApiException(500, "Something went wrong on our end. Please try again."); }
        return out;
    }

    static Map<String, Object> getOne(int id) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(BASE_SELECT + " WHERE t.id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ApiException(404, "Treatment not found.");
                return toMap(rs);
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) { System.err.println("TreatmentHandler failed: " + e); e.printStackTrace(); throw new ApiException(500, "Something went wrong on our end. Please try again."); }
    }

    static Map<String, Object> toMap(ResultSet rs) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getInt("id"));
        m.put("name", rs.getString("name"));
        m.put("category", rs.getString("category"));
        m.put("description", rs.getString("description"));
        m.put("costMin", rs.getInt("cost_min"));
        m.put("costMax", rs.getInt("cost_max"));
        m.put("durationDays", rs.getInt("duration_days"));
        m.put("hospitalId", rs.getInt("hospital_id"));
        m.put("hospitalName", rs.getString("hospital_name"));
        m.put("city", rs.getString("hospital_city"));
        return m;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
