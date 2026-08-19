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
 * GET /api/hospitals            -> list all hospitals
 * GET /api/hospitals?city=Delhi -> filter by city
 * GET /api/hospitals?q=apollo   -> search by name
 */
public class HospitalHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (Http.handleCors(ex)) return;
        try {
            if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
                throw new ApiException(405, "This HTTP method isn't supported for this endpoint.");
            }
            Map<String, String> q = Http.parseQuery(ex.getRequestURI().getRawQuery());
            List<Object> results = search(q.get("city"), q.get("q"));
            Http.sendObject(ex, 200, results);
        } catch (Throwable t) {
            Http.sendApiError(ex, t);
        }
    }

    static List<Object> search(String city, String q) {
        String cityFilter = blankToNull(city);
        String search = blankToNull(q);

        StringBuilder sql = new StringBuilder(
                "SELECT id, name, city, description, rating, image_url FROM hospitals WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (cityFilter != null) {
            sql.append(" AND city = ?");
            params.add(cityFilter);
        }
        if (search != null) {
            sql.append(" AND LOWER(name) LIKE LOWER(?)");
            params.add("%" + search + "%");
        }
        sql.append(" ORDER BY rating DESC");

        List<Object> out = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(toMap(rs));
                }
            }
        } catch (Exception e) {
            throw new ApiException(500, "Something went wrong on our end. Please try again.");
        }
        return out;
    }

    static Map<String, Object> toMap(ResultSet rs) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getInt("id"));
        m.put("name", rs.getString("name"));
        m.put("city", rs.getString("city"));
        m.put("description", rs.getString("description"));
        m.put("rating", rs.getDouble("rating"));
        m.put("imageUrl", rs.getString("image_url"));
        return m;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
